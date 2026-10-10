"""Day 29: token profile of the chat-with-memory mode. Replays a scenario through /api/sessions on a fresh web console (local provider, final
prompt profile, a roomy 16K context so nothing is truncated) and prints prompt/completion sizes per call purpose, to size `num_ctx` and token limits.
  python3 rag/eval/local-rag/session_profile.py [scenario-id] [--tuning "numCtx=16384"]"""
import json
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import optimize as O  # noqa: E402

scenario = next((a for a in sys.argv[1:] if not a.startswith("--")), "knee-strength-plan")
tuning = sys.argv[sys.argv.index("--tuning") + 1] if "--tuning" in sys.argv else "numCtx=16384"
turns = json.loads((O.ROOT / f"rag/eval/scenarios/{scenario}.json").read_text())["turns"]
cfg = {"model": O.FAST, "tuning": tuning, "env": {"RAG_PROMPT_PROFILE": "local"}}  # explicit: the Day 29 defaults
O.OUT_DIR.mkdir(exist_ok=True)
proc = O.start_server(cfg)
try:
    sid = O.post("/api/sessions")["session"]["id"]
    O.post("/api/llm/stats/reset")
    t0 = time.time()
    for i, t in enumerate(turns):
        body = json.dumps({"text": t["user"], "options": {"provider": "ollama", "memoryMode": "FULL"}})
        r = urllib.request.Request(f"http://localhost:{O.PORT}/api/sessions/{sid}/messages", body.encode(), {"Content-Type": "application/json"})
        try:
            urllib.request.urlopen(r, timeout=600).read()
            print(f"turn {i + 1}/{len(turns)} ok", flush=True)
        except Exception as e:  # noqa: BLE001
            print(f"turn {i + 1} failed: {e}", flush=True)
    print(f"{len(turns)} turns in {time.time() - t0:.0f}s\n")
    print(f"{'purpose':10}{'calls':>6}{'prompt avg':>11}{'p90':>6}{'max':>6}{'compl avg':>10}{'p90':>6}{'max':>6}{'gen tok/s':>10}{'avg ms':>8}")
    for p in O.get("/api/llm/stats"):
        print(f"{p['purpose']:10}{p['calls']:>6}{p['promptTokensAvg']:>11.0f}{p['promptTokensP90']:>6}{p['promptTokensMax']:>6}{p['completionTokensAvg']:>10.0f}"
              f"{p['completionTokensP90']:>6}{p['completionTokensMax']:>6}{p['generationTokPerSec']:>10.1f}{p['totalMillisAvg']:>8.0f}")
    O.urllib.request.urlopen(urllib.request.Request(f"http://localhost:{O.PORT}/api/sessions/{sid}", method="DELETE"), timeout=30)
finally:
    proc.terminate()
    proc.wait(timeout=30)
