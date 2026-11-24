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

--log prints every request line on stdout, with a mark for the held ones.

When one side ends, the other side gets the rest of the data and then a clean close (half-close),
not a reset: mpv (ffmpeg) reports "Connection reset by peer" otherwise.
"""
import argparse
import os
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

# One release per held request, handed out by the key press or the file.
release_lock = threading.Condition()
release_tokens = 0


def give_release():
    global release_tokens
    with release_lock:
        release_tokens += 1
        release_lock.notify_all()


def wait_release(timeout):
    global release_tokens
    deadline = time.monotonic() + timeout if timeout else None
    with release_lock:
        while release_tokens == 0:
            left = None if deadline is None else deadline - time.monotonic()
            if left is not None and left <= 0:
                return False
            release_lock.wait(left)
        release_tokens -= 1
        return True


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
                pass
            give_release()
        time.sleep(0.2)


def is_held(method, path):
    return any(m in ("*", method) and path.startswith(p) for m, p in holds)


def log(text):
    if args.log:
        print(f"{time.strftime('%H:%M:%S')} {text}", flush=True)


def forward_requests(src, dst):
    """Client to server: pass requests through, holding the chosen ones, until the stream ends."""
    buf = b""
    remaining = 0  # body bytes of the current request still to pass
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
            buf = buf[take:]
            remaining -= take
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
        log(f"{'HELD ' if held else ''}{method} {path}")
        if held:
            hint = f" or create {args.release_file}" if args.release_file else ""
            log(f"holding {method} {path}: press Enter{hint}")
            released = wait_release(args.hold_max)
            log(f"{'released' if released else 'hold timed out, passing'} {method} {path}")
        dst.sendall(head)
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
            forward_requests(src, dst)
        else:
            while data := src.recv(8192 if rate else 65536):
                dst.sendall(data)
                if rate:
                    time.sleep(len(data) / rate)
    except OSError:
        clean = False
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
    """True for the second direction that ends: only then both sockets are closed."""

    def __init__(self):
        self.lock = threading.Lock()
        self.count = 0

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
