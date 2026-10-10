#!/usr/bin/env bash
# Private local-LLM service on the LAN: ai-gateway (via LAN forwarder :8088) in front of a loopback-only Ollama.
#
#   scripts/private-ai.sh start                 run the gateway (prints URL + QR)
#   scripts/private-ai.sh keygen <device>       create a device key (printed once, only its hash is stored)
#   scripts/private-ai.sh ollama-env            print the Ollama settings to apply (does NOT touch the service)
#   scripts/private-ai.sh warmup                load the model into memory once
#   scripts/private-ai.sh url                   print the URLs / QR only
#
# Default topology: phone -> lan_forward.py (/usr/bin/python3, 0.0.0.0:8088) -> gateway (127.0.0.1:8089) -> Ollama (127.0.0.1:11434).
# The forwarder exists because MDM-managed Macs block incoming LAN connections for every `java`. Direct mode
# (GATEWAY_HOST=0.0.0.0, no forwarder) is for Macs without that restriction.
#
# Env: GATEWAY_CONFIG (default ~/.config/ai-gateway/gateway.json - keep it OUT of the repo)
#      LAN_PORT (8088, forwarder) GATEWAY_PORT (8089) GATEWAY_HOST (127.0.0.1; 0.0.0.0 = direct mode, port defaults to LAN_PORT) NUM_PARALLEL (1) KEEP_ALIVE (30m) WARMUP=1 to preheat on start
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="${GATEWAY_CONFIG:-$HOME/.config/ai-gateway/gateway.json}"
HOST="${GATEWAY_HOST:-127.0.0.1}"
LAN_PORT="${LAN_PORT:-8088}"
if [ "$HOST" = "0.0.0.0" ]; then PORT="${GATEWAY_PORT:-$LAN_PORT}"; else PORT="${GATEWAY_PORT:-8089}"; fi
NUM_PARALLEL="${NUM_PARALLEL:-1}"
KEEP_ALIVE="${KEEP_ALIVE:-30m}"
OLLAMA_URL="${GATEWAY_UPSTREAM_URL:-http://127.0.0.1:11434}"
BIN="$ROOT/ai-gateway/build/install/ai-gateway/bin/ai-gateway"

build() { (cd "$ROOT" && ./gradlew --max-workers=2 -q :ai-gateway:installDist); }

lan_ip() { ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "<lan-ip>"; }

print_urls() {
  local ip name; ip="$(lan_ip)"; name="$(scutil --get LocalHostName 2>/dev/null || hostname -s).local"
  echo "Open on the phone (same Wi-Fi):"
  echo "  http://$ip:$LAN_PORT/"
  echo "  http://$name:$LAN_PORT/   (may not resolve on this network; prefer the IP)"
  if command -v qrencode >/dev/null 2>&1; then
    qrencode -t ANSIUTF8 "http://$ip:$LAN_PORT/"
  else
    echo "(install 'brew install qrencode' to also get a QR code)"
  fi
}

ollama_env() {
  cat <<EOT
# Apply to the Ollama service yourself (then restart Ollama); this script never does it.
launchctl setenv OLLAMA_NUM_PARALLEL $NUM_PARALLEL     # must match limits.maxConcurrent in the gateway config
launchctl setenv OLLAMA_KEEP_ALIVE $KEEP_ALIVE
# Ollama must stay on loopback (default). Do NOT set OLLAMA_HOST=0.0.0.0.
EOT
}

warmup() {
  local model
  model="$(python3 -c "import json,os,sys;p=sys.argv[1];print((json.load(open(p))['models'] if os.path.exists(p) else ['gemma4:26b-a4b-it-qat'])[0])" "$CONFIG")"
  echo "Warming up $model ..."
  curl -fsS "$OLLAMA_URL/api/generate" -d "{\"model\":\"$model\",\"prompt\":\"\",\"keep_alive\":\"$KEEP_ALIVE\"}" >/dev/null
}

case "${1:-start}" in
  keygen)
    [ -n "${2:-}" ] || { echo "usage: $0 keygen <device-name>" >&2; exit 2; }
    build; mkdir -p "$(dirname "$CONFIG")"
    "$BIN" keygen --name "$2" --config "$CONFIG" ;;
  ollama-env) ollama_env ;;
  warmup) warmup ;;
  url) print_urls ;;
  start)
    [ -f "$CONFIG" ] || { echo "No config at $CONFIG. Copy ai-gateway/gateway.example.json there, then run: $0 keygen <device>" >&2; exit 1; }
    build
    ollama_env
    [ "${WARMUP:-0}" = 1 ] && warmup
    print_urls
    FWD_PID=""; GW_PID=""
    stop_all() { trap - INT TERM EXIT; [ -n "$FWD_PID" ] && kill "$FWD_PID" 2>/dev/null; [ -n "$GW_PID" ] && kill "$GW_PID" 2>/dev/null; exit 0; }
    trap stop_all INT TERM EXIT
    if [ "$HOST" != "0.0.0.0" ]; then
      /usr/bin/python3 "$ROOT/scripts/lan_forward.py" "$LAN_PORT" "$PORT" & FWD_PID=$!
    fi
    # caffeinate keeps the Mac awake while the gateway runs
    caffeinate -i -w $$ & # lives as long as this script
    "$BIN" --config "$CONFIG" --host "$HOST" --port "$PORT" & GW_PID=$!
    wait "$GW_PID" ;;
  *) sed -n '2,9p' "$0"; exit 2 ;;
esac
