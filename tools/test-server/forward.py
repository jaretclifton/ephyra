#!/usr/bin/env python3
"""A second door to the test server, to take the server away from one emulator only.

forward.py [listen-port] [server-port] [kbit/s] [--hold METHOD PATH-PREFIX]... [--release-file FILE]
           [--log]                                     default 8097 -> 8096, as fast as it goes

Point one emulator's app at http://10.0.2.2:<listen-port>. Stop this script (Ctrl-C, or kill it)
and that emulator has lost the server, with its network still up: open connections are cut and
new ones are refused. Start it again and the server is back. The server itself and every other
emulator, on the server's own port, notice nothing.

With a third argument the server's answers are slowed to that many kbit/s per connection. A player
buffers a whole test file within a second otherwise, and then plays on without the server: give a
rate just above the file's bitrate (the 12 minute files are about 430 kbit/s) to cut a stream that
is still loading. The rate limit slows long answers only; a small one passes in one chunk.

--hold METHOD PATH-PREFIX holds one chosen request (repeatable): a request whose method equals
METHOD and whose path starts with PATH-PREFIX is not passed to the server until it is released, so
the server answers late. The rest passes at once. METHOD may be * for any method. Example, a stop
report that arrives late (#890):

    forward.py 8984 8096 --hold POST /Sessions/Playing/Stopped

A held request is released (the oldest first, one per release) by pressing Enter in the terminal
that runs the script, or when the file named by --release-file exists (the script deletes the file
when it takes it, so create it again for the next one). Without a terminal (no stdin) the file is
the only way. --hold-max SECONDS releases by itself after that long (default: never).

Held requests wait in arrival order and a release frees the oldest; a release while nothing is
held is dropped. A request whose connection fails while it waits is forgotten.

--log prints on stdout, per request with a number (#n): its request line when it arrives (with a mark
for a held one), when it is released, when it is forwarded to the server, and the first 1000 bytes
of its body (a playback report's JSON, with its PlayMethod). The order of the "forwarded" lines is
the order the server saw the requests in.

When one side ends, the other side gets the rest of the data and then a clean close (half-close),
not a reset: mpv (ffmpeg) reports "Connection reset by peer" otherwise.
"""
import argparse
import os
import select
import socket
import sys
import threading
import time

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("listen_port", nargs="?", type=int, default=8097)
parser.add_argument("server_port", nargs="?", type=int, default=8096)
parser.add_argument("kbit", nargs="?", type=int, default=0, help="slow the server's answers to this many kbit/s")
parser.add_argument("--hold", nargs=2, action="append", default=[], metavar=("METHOD", "PATH_PREFIX"))
parser.add_argument("--release-file", metavar="FILE")
parser.add_argument("--hold-max", type=float, default=0, metavar="SECONDS")
parser.add_argument("--log", action="store_true")
args = parser.parse_args()

listen_port = args.listen_port
server_port = args.server_port
bytes_per_second = args.kbit * 125
holds = [(m.upper(), p) for m, p in args.hold]
inspect = bool(holds) or args.log

# Held requests wait in arrival order; each release (key press or file) frees the oldest one.
# A release while nothing is held is dropped, so it never lets a later request through.
waiters_lock = threading.Lock()
waiters = []


class Waiter:
    def __init__(self):
        self.event = threading.Event()
        self.released = False


def give_release():
    with waiters_lock:
        if waiters:
            w = waiters.pop(0)
            w.released = True
            w.event.set()


def client_reset(sock):
    """True when the client's side of the connection has failed (a reset), not merely sent a FIN."""
    try:
        if select.select([sock], [], [], 0)[0]:
            sock.recv(1, socket.MSG_PEEK)
    except OSError:
        return True
    return False


def wait_release(conn, sock, timeout):
    """Returns "released", "timeout" or "cancelled" (the connection failed while held)."""
    w = Waiter()
    with waiters_lock:
        if conn.failed:
            return "cancelled"
        waiters.append(w)
        conn.waiters.append(w)
    deadline = time.monotonic() + timeout if timeout else None
    while not w.event.wait(0.2):
        if client_reset(sock):
            conn.fail()
        elif deadline is not None and time.monotonic() >= deadline:
            break
    with waiters_lock:
        if w in waiters:
            waiters.remove(w)
        if w in conn.waiters:
            conn.waiters.remove(w)
        if w.released:
            return "released"
        return "cancelled" if conn.failed else "timeout"


def watch_stdin():
    try:
        while sys.stdin.readline():
            give_release()
    except (OSError, ValueError):
        pass


def watch_file(path):
    while True:
        if os.path.exists(path):
            try:
                os.remove(path)
            except OSError:
                time.sleep(0.2)  # not taken: no release, try again
                continue
            give_release()
        time.sleep(0.2)


def is_held(method, path):
    return any(m in ("*", method) and path.startswith(p) for m, p in holds)


def log(text):
    if args.log:
        print(f"{time.strftime('%H:%M:%S')} {text}", flush=True)


request_ids = iter(range(1, 1 << 62))  # next() is atomic in CPython


def log_body(rid, body):
    if body:
        log(f"#{rid} body: " + body[:1000].decode("utf-8", "replace") + (" ..." if len(body) > 1000 else ""))


def forward_requests(src, dst, conn):
    """Client to server: pass requests through, holding the chosen ones, until the stream ends."""
    buf = b""
    remaining = 0  # body bytes of the current request still to pass
    rid = 0  # the current request's number in the log
    body = b""  # the part of the current body seen so far, for --log
    raw = False  # chunked or upgraded: the rest of the connection passes untouched
    while True:
        if not buf:
            buf = src.recv(65536)
            if not buf:
                return
        if raw:
            dst.sendall(buf)
            buf = b""
            continue
        if remaining:
            take = min(remaining, len(buf))
            dst.sendall(buf[:take])
            if args.log and len(body) < 1001:
                body += buf[:take]
            buf = buf[take:]
            remaining -= take
            if not remaining:
                log_body(rid, body)
            continue
        end = buf.find(b"\r\n\r\n")
        if end < 0:
            more = src.recv(65536)
            if not more:
                dst.sendall(buf)
                return
            buf += more
            continue
        head, buf = buf[: end + 4], buf[end + 4 :]
        lines = head.decode("latin-1").split("\r\n")
        parts = lines[0].split(" ")
        method, path = parts[0].upper(), parts[1] if len(parts) > 1 else ""
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        held = is_held(method, path)
        rid = next(request_ids)
        log(f"#{rid} {'HELD ' if held else ''}{method} {path}")
        if held:
            hint = f" or create {args.release_file}" if args.release_file else ""
            log(f"#{rid} holding: press Enter{hint}")
            outcome = wait_release(conn, src, args.hold_max)
            if outcome == "cancelled":
                log(f"#{rid} connection lost while held")
                raise ConnectionError("client connection lost while a request was held")
            log(f"#{rid} {'released' if outcome == 'released' else 'hold timed out'}")
        dst.sendall(head)
        log(f"#{rid} forwarded")
        body = b""
        if "chunked" in headers.get("transfer-encoding", "").lower() or headers.get("upgrade"):
            raw = True
        else:
            try:
                remaining = int(headers.get("content-length", "0"))
            except ValueError:
                remaining = 0
def pump(src, dst, done, rate=0, requests=False):
    clean = True
    try:
        if requests:
            forward_requests(src, dst, done)
        else:
            while data := src.recv(8192 if rate else 65536):
                dst.sendall(data)
                if rate:
                    time.sleep(len(data) / rate)
    except OSError:
        clean = False
        done.fail()
    try:
        if clean:
            dst.shutdown(socket.SHUT_WR)  # half-close: the peer reads to the end, then sees EOF
    except OSError:
        pass
    if not clean or done.release_and_check():
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            s.close()


class Done:
    """One connection's state: both sockets are closed when the second direction ends, or at once on a failure."""

    def __init__(self):
        self.lock = threading.Lock()
        self.count = 0
        self.failed = False
        self.waiters = []  # held requests of this connection

    def fail(self):
        with waiters_lock:
            self.failed = True
            for w in self.waiters:
                if w in waiters:
                    waiters.remove(w)
                w.event.set()

    def release_and_check(self):
        with self.lock:
            self.count += 1
            return self.count >= 2


def serve(client):
    try:
        server = socket.create_connection(("127.0.0.1", server_port), timeout=10)
        server.settimeout(None)
    except OSError:
        client.close()
        return
    done = Done()
    threading.Thread(target=pump, args=(client, server, done, 0, inspect), daemon=True).start()
    pump(server, client, done, bytes_per_second)


listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
if sys.platform == "win32":
    # SO_REUSEADDR on Windows lets a second process bind to a port that is in use (#941)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
else:
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    listener.bind(("0.0.0.0", listen_port))
except OSError as e:
    sys.exit(f"cannot listen on :{listen_port}: {e} (another forward.py on this port?)")
listener.listen(64)
limit = f", {bytes_per_second // 125} kbit/s per connection" if bytes_per_second else ""
print(f"forwarding :{listen_port} -> 127.0.0.1:{server_port}{limit}", flush=True)
for m, p in holds:
    print(f"holding {m} {p}* until Enter" + (f" or {args.release_file}" if args.release_file else ""), flush=True)
if holds:
    threading.Thread(target=watch_stdin, daemon=True).start()
    if args.release_file:
        threading.Thread(target=watch_file, args=(args.release_file,), daemon=True).start()
try:
    while True:
        conn, _ = listener.accept()
        threading.Thread(target=serve, args=(conn,), daemon=True).start()
except KeyboardInterrupt:
    pass
