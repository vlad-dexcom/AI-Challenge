"""Day 29: runs configurations of the local model (model, tuning, prompt profile, ...) through a fresh web console per configuration
and records quality, speed, resources and per-call-type token statistics.

Prerequisites: `./gradlew :web-console:installDist`, Ollama running, models pulled. Example:
  python3 rag/eval/local-rag/optimize.py --configs baseline-fast,ctx8k-fast --sets tuning,holdout --repeats 3
Results: rag/eval/local-rag/optimization/<config>.json (one file per configuration, re-run replaces it). `optimize_report.py` prints the tables.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import benchmark as B  # noqa: E402  (questions, call(), score())

ROOT = HERE.parents[2]
OUT_DIR = HERE / "optimization"
PORT = 8091
FAST = "gemma4:26b-a4b-it-qat"
SMART = "gemma4:31b-mlx"

# name -> configuration. `tuning` is OLLAMA_TUNING, `env` extra environment variables of the web console (e.g. the prompt profile).
CONFIGS = {
    "baseline-fast": {"model": FAST, "tuning": ""},
    "baseline-smart": {"model": SMART, "tuning": ""},
}


def load_config_file():
    """Extra configurations can be added in optimization/configs.json: {"name": {"model": ..., "tuning": ..., "env": {...}}}."""
    f = OUT_DIR / "configs.json"
    if f.is_file():
        CONFIGS.update(json.loads(f.read_text()))


def holdout_questions():
    f = HERE / "holdout.json"
    if not f.is_file():
        return []
    qs = []
    for c in json.loads(f.read_text())["questions"]:
        ru = sum("а" <= ch.lower() <= "я" for ch in c["question"]) > 3
        qs.append(dict(id=c["id"], lang="ru" if ru else "en", question=c["question"], cat=c["category"], must=c["mustContain"],
                       exp=[(e["source"], e["section"].lower()) for e in c["expectedSources"]]))
    return qs


def get(path):
    return json.load(urllib.request.urlopen(f"http://localhost:{PORT}{path}", timeout=30))


def post(path, body="{}"):
    r = urllib.request.Request(f"http://localhost:{PORT}{path}", body.encode(), {"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(r, timeout=30))


def ollama_ps():
    try:
        j = json.load(urllib.request.urlopen("http://localhost:11434/api/ps", timeout=10))
        return [dict(name=m["name"], size_gb=round(m["size"] / 1e9, 2), size_vram_gb=round(m.get("size_vram", 0) / 1e9, 2), context=m.get("context_length")) for m in j.get("models", [])]
    except Exception as e:  # noqa: BLE001
        return [dict(error=str(e)[:100])]


def ollama_rss_gb():
    out = subprocess.run(["ps", "-axo", "rss,comm"], capture_output=True, text=True).stdout
    kb = sum(int(l.split(None, 1)[0]) for l in out.splitlines()[1:] if "ollama" in l.lower())
    return round(kb / 1e6, 2)


def unload_all():
    for m in ollama_ps():
        if "name" in m:
            subprocess.run(["ollama", "stop", m["name"]], capture_output=True)
    time.sleep(3)


def start_server(cfg):
    # Since Day 29 the local defaults are the tuned ones; configurations that do not say otherwise keep the ORIGINAL behaviour (no tuning, original prompts).
    env = dict({"RAG_PROMPT_PROFILE": "default"}, **cfg.get("env", {}))
    env = dict(os.environ, **env)
    env.pop("GEMINI_API_KEY", None)
    args = [str(ROOT / "web-console/build/install/web-console/bin/web-console"), "--port", str(PORT), "--ollama-model", cfg["model"],
            "--ollama-tuning", cfg.get("tuning") or "none"]
    log = open(OUT_DIR / "server.log", "w")
    proc = subprocess.Popen(args, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(60):
        try:
            get("/api/chat/config")
            return proc
        except Exception:  # noqa: BLE001
            time.sleep(1)
    proc.kill()
    raise RuntimeError("web console did not start, see optimization/server.log")


def run_set(name, questions, repeats, model):
    runs = []
    for rep in range(repeats):
        post("/api/llm/stats/reset")
        recs, t0 = [], time.time()
        for q in questions:
            t, wall, err = B.call("ollama", model, q["question"])
            rec = {"id": q["id"], "lang": q["lang"], "cat": q["cat"], "wall_ms": round(wall * 1000)}
            if t is None or t.get("error"):
                rec["error"] = err or t.get("error")
            else:
                rec.update(latency_ms=t["latencyMs"], llm_calls=t.get("llmCalls"), answer=t.get("answer"), scores=B.score(q, t),
                           sources=[x["label"] for x in t.get("sources") or []])
            recs.append(rec)
            print(f"  {name} rep{rep} {q['id']:8} {rec.get('latency_ms')} ms {rec.get('error') or ''}", flush=True)
        runs.append({"run": recs, "total_s": round(time.time() - t0, 1), "call_stats": get("/api/llm/stats")})
    return runs


def run_config(name, cfg, sets, repeats):
    print(f"== {name}: {cfg}", flush=True)
    unload_all()
    proc = start_server(cfg)
    try:
        B.URL = f"http://localhost:{PORT}/api/chat"
        t, wall, err = B.call("ollama", cfg["model"], "Say hi in one word.", mode="no_rag")
        result = {"config": cfg, "cold_start_ms": round(wall * 1000), "cold_error": err, "sets": {}}
        B.call("ollama", cfg["model"], "Say hi in one word.", mode="no_rag")
        for set_name in sets:
            qs = B.Q if set_name == "tuning" else holdout_questions()
            if not qs:
                print(f"  (no questions for set {set_name}, skipped)")
                continue
            result["sets"][set_name] = run_set(set_name, qs, repeats, cfg["model"])
        result["resources"] = {"ollama_ps": ollama_ps(), "ollama_rss_gb": ollama_rss_gb()}
        (OUT_DIR / f"{name}.json").write_text(json.dumps(result, ensure_ascii=False, indent=1))
    finally:
        proc.terminate()
        proc.wait(timeout=30)


if __name__ == "__main__":
    OUT_DIR.mkdir(exist_ok=True)
    load_config_file()
    ap = argparse.ArgumentParser()
    ap.add_argument("--configs", required=True, help="comma separated names; known: " + ", ".join(CONFIGS))
    ap.add_argument("--sets", default="tuning", help="tuning,holdout")
    ap.add_argument("--repeats", type=int, default=3)
    a = ap.parse_args()
    for n in a.configs.split(","):
        run_config(n, CONFIGS[n], a.sets.split(","), a.repeats)
    print("ALLDONE")
