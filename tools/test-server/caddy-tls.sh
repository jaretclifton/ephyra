#!/usr/bin/env bash
# Caddy with TLS in front of the test server, to test players against a reverse proxy that asks
# for a client certificate (#960) or requires one (#993). A throwaway CA signs the server
# certificate; the same CA is the client trust pool.
#
#   tools/test-server/caddy-tls.sh          start, or restart with a fresh Caddyfile
#   tools/test-server/caddy-tls.sh stop     remove the container (the certificates stay)
#   tools/test-server/caddy-tls.sh status   which sites answer, and the TLS version they speak
#
# Sites, on host ports BASE to BASE+5 (default 8443 to 8448):
#   BASE      client_auth verify_if_given: asks for a certificate, plays without one
#   BASE+1    no client_auth: the control, same certificate
#   BASE+2    verify_if_given, TLS 1.2 only
#   BASE+3    verify_if_given, TLS 1.3 only
#   BASE+4    no client_auth, TLS 1.2 only
#   BASE+5    client_auth require_and_verify: no certificate, no connection
#
# From an emulator the address is https://<HOST>:<port>; HOST defaults to a nip.io name that
# resolves to 10.0.2.2, so the client sends SNI as it does for a real host name. The app trusts
# the CA once it is a user CA on the emulator (the network security config trusts user CAs);
# docs/dev-environment.md says how. mpv does not check the server certificate (tls-verify=no).
#
# Settings (environment):
#   EPHYRA_TEST_SERVER_DIR    the test server's folder; the certificates go in caddy-tls/ there
#   EPHYRA_TEST_SERVER_PORT   the test server's port (default 8096)
#   EPHYRA_CADDY_PORT         BASE (default 8443)
#   EPHYRA_CADDY_HOST         the server name (default jellyfin.10.0.2.2.nip.io)
#   EPHYRA_CADDY_IMAGE        Caddy image (default below)
set -euo pipefail

ROOT="${EPHYRA_TEST_SERVER_DIR:-$HOME/ephyra-test-server}"
JF_PORT="${EPHYRA_TEST_SERVER_PORT:-8096}"
BASE="${EPHYRA_CADDY_PORT:-8443}"
HOST="${EPHYRA_CADDY_HOST:-jellyfin.10.0.2.2.nip.io}"
IMAGE="${EPHYRA_CADDY_IMAGE:-caddy:2.11}"
NAME=ephyra-test-caddy
DIR="$ROOT/caddy-tls"

command -v docker >/dev/null || { echo "docker not found (on Windows: run this inside WSL)" >&2; exit 1; }

status() {
  docker ps -a --filter "name=^$NAME\$" --format '{{.Names}}: {{.Status}}'
  for p in $(seq "$BASE" $((BASE + 5))); do
    printf '%s: ' "$p"
    { echo | openssl s_client -connect "localhost:$p" -servername "$HOST" 2>/dev/null |
      grep -E '^ *Protocol *:|Acceptable client certificate CA names|No client certificate CA names' |
      sed 's/^ *//' | tr '\n' ' '; } || printf 'no answer'
    echo
  done
}

case "${1:-start}" in
  stop) docker rm -f "$NAME" >/dev/null 2>&1 || true; echo "stopped $NAME"; exit 0 ;;
  status) status; exit 0 ;;
  start) ;;
  *) echo "usage: $0 [start|stop|status]" >&2; exit 2 ;;
esac

command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 1; }
mkdir -p "$DIR"
cd "$DIR"
if [ ! -f ca.crt ] || ! openssl x509 -checkend 86400 -noout -in server.crt >/dev/null 2>&1; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj "/CN=Ephyra test CA" \
    -keyout ca.key -out ca.crt 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -subj "/CN=$HOST" -keyout server.key -out server.csr 2>/dev/null
  printf 'subjectAltName=DNS:%s,DNS:localhost,IP:10.0.2.2,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' \
    "$HOST" > server.ext
  openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 365 \
    -extfile server.ext -out server.crt 2>/dev/null
  # A client certificate from the same CA, for the client certificate work (#993).
  openssl req -newkey rsa:2048 -nodes -subj "/CN=ephyra-test-client" \
    -keyout client.key -out client.csr 2>/dev/null
  printf 'extendedKeyUsage=clientAuth\n' > client.ext
  openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 365 \
    -extfile client.ext -out client.crt 2>/dev/null
  # The Caddy image runs as root; the key only has to be readable inside the container.
  chmod 644 server.key
  rm -f ./*.csr
fi

site() { # site <port> <client_auth mode or none> [protocols]
  echo "https://$HOST:$1, https://localhost:$1 {"
  echo "  tls /certs/server.crt /certs/server.key {"
  [ -n "${3:-}" ] && echo "    protocols $3"
  if [ "$2" != none ]; then
    # The reporter's form in #960: a file trust pool with the own CA.
    echo "    client_auth {"
    echo "      mode $2"
    echo "      trust_pool file {"
    echo "        pem_file /certs/ca.crt"
    echo "      }"
    echo "    }"
  fi
  echo "  }"
  echo "  reverse_proxy host.docker.internal:$JF_PORT"
  echo "}"
}
{
  echo "{"
  echo "  auto_https off"
  echo "  admin off"
  echo "}"
  site "$BASE" verify_if_given
  site $((BASE + 1)) none
  site $((BASE + 2)) verify_if_given "tls1.2 tls1.2"
  site $((BASE + 3)) verify_if_given "tls1.3 tls1.3"
  site $((BASE + 4)) none "tls1.2 tls1.2"
  site $((BASE + 5)) require_and_verify
} > Caddyfile

docker rm -f "$NAME" >/dev/null 2>&1 || true
# Published on 0.0.0.0 like the test server, so that on Windows the emulators reach it through
# WSL's port forwarding (Caddy on the host network listens on IPv6 only, which WSL forwards to ::1).
docker run -d --name "$NAME" --restart unless-stopped \
  --add-host host.docker.internal:host-gateway \
  -p "0.0.0.0:$BASE-$((BASE + 5)):$BASE-$((BASE + 5))" \
  -v "$DIR/Caddyfile:/etc/caddy/Caddyfile:ro" -v "$DIR:/certs:ro" "$IMAGE" >/dev/null
sleep 3
status
echo "CA for the emulator: $DIR/ca.crt   addresses: https://$HOST:$BASE … https://$HOST:$((BASE + 5))"
