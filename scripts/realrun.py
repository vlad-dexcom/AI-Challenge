#!/usr/bin/env python3
"""Day 30: real-model checks of the private AI service (matrix of OLLAMA_NUM_PARALLEL, soak, faults, limits) over the LAN address.

It starts its OWN Ollama instance on 127.0.0.1:11436 (same model store, the brew service on 11434 is not touched), the gateway on
127.0.0.1:8089 and the LAN forwarder (Apple python, 0.0.0.0:8088), then calls the service via the Mac's LAN IP like a phone would.
Nothing else should use the GPU while it runs.

  python3 scripts/realrun.py matrix                 # NUM_PARALLEL 1/2/4 x clients 1/2/4/8
  python3 scripts/realrun.py soak --np 4 --clients 3 --minutes 15
  python3 scripts/realrun.py faults --np 2          # Ollama restart under load, client disconnects
  python3 scripts/realrun.py limits                 # rate limit, context, body, max_tokens, queue (strict config)
  python3 scripts/realrun.py serve --np 4           # keep the service up for manual tests (phone); prints URL and a fresh key
Results are merged into docs/day30-results.json.
"""
import argparse
import json
import os
import re
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BIN = ROOT / "ai-gateway/build/install/ai-gateway/bin/ai-gateway"
RESULTS = ROOT / "docs/day30-results.json"
OLLAMA_PORT, GW_PORT, LAN_PORT = 11436, 8089, 8088
MODEL = os.environ.get("MODEL", "trainer-rag-fast")
PROMPT = "Explain progressive overload for a beginner in three sentences."
WORK = Path("/tmp/day30-realrun")


def lan_ip():
    return subprocess.run(["ipconfig", "getifaddr", "en0"], capture_output=True, text=True).stdout.strip()


def http(method, url, body=None, key=None, timeout=60):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = f"Bearer {key}"
    req = urllib.request.Request(url, json.dumps(body).encode() if body is not None else None, headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()
    except Exception as e:  # noqa: BLE001
        return 0, {}, str(e)


class Stack:
    """Ollama test instance + gateway + LAN forwarder; `limits` overrides entries of the gateway limits."""

    def __init__(self, num_parallel=1, limits=None, fixed_key=None):
        self.np, self.limits, self.procs, self.key, self.fixed_key = num_parallel, limits or {}, {}, None, fixed_key

    def start_ollama(self):
        env = dict(os.environ, OLLAMA_HOST=f"127.0.0.1:{OLLAMA_PORT}", OLLAMA_NUM_PARALLEL=str(self.np), OLLAMA_MAX_LOADED_MODELS="2",
                   OLLAMA_KEEP_ALIVE="30m", OLLAMA_FLASH_ATTENTION="1", OLLAMA_KV_CACHE_TYPE="q8_0")
        self.procs["ollama"] = subprocess.Popen(["ollama", "serve"], env=env, stdout=open(WORK / "ollama.log", "a"), stderr=subprocess.STDOUT)
        for _ in range(60):
            if http("GET", f"http://127.0.0.1:{OLLAMA_PORT}/api/version")[0] == 200:
                return
            time.sleep(1)
        raise RuntimeError("ollama test instance did not start")

    def stop_ollama(self):
        p = self.procs.pop("ollama", None)
        if p:
            p.send_signal(signal.SIGTERM)
            try:
                p.wait(timeout=30)
            except subprocess.TimeoutExpired:
                p.kill()

    def warm(self):
        http("POST", f"http://127.0.0.1:{OLLAMA_PORT}/api/chat", {"model": MODEL, "messages": [{"role": "user", "content": "hi"}], "stream": False,
                                                               "options": {"num_predict": 4}}, timeout=300)

    def start(self):
        WORK.mkdir(exist_ok=True)
        subprocess.run(["ollama", "stop", MODEL], capture_output=True)  # free the brew instance
        self.start_ollama()
        limits = {"maxBodyBytes": 65536, "maxMessages": 40, "maxTokensCap": 1024, "defaultMaxTokens": 512, "rateLimitPerMinute": 100000, "rateBurst": 100000,
                  "maxConcurrent": self.np, "queueSize": 16, "queueTimeoutMs": 300000, "retryAfterSeconds": 5, "maxRequestsPerKey": 64,
                  "requestTimeoutMs": 300000, "streamIdleTimeoutMs": 120000, "keepaliveMs": 10000}
        limits.update(self.limits)
        cfg = {"host": "127.0.0.1", "port": GW_PORT, "upstreamUrl": f"http://127.0.0.1:{OLLAMA_PORT}", "models": [MODEL], "numCtx": 8192, "keys": [], "limits": limits,
               "rag": {"enabled": True, "indexDir": str(ROOT / "rag/index-local"), "sessionsDir": str(ROOT / "rag/sessions-gateway")}}
        cfgfile = WORK / "gateway.json"
        cfgfile.write_text(json.dumps(cfg, indent=1))
        if self.fixed_key:  # keep a device key across restarts (the phone remembers it)
            import hashlib
            cfg["keys"] = [{"name": "device", "sha256": hashlib.sha256(self.fixed_key.encode()).hexdigest()}]
            cfgfile.write_text(json.dumps(cfg, indent=1))
            self.key = self.fixed_key
        else:
            out = subprocess.run([str(BIN), "keygen", "--name", "realrun", "--config", str(cfgfile)], capture_output=True, text=True).stdout
            self.key = re.search(r"(sk-[A-Za-z0-9_-]{20,})", out).group(1)
        self.procs["gateway"] = subprocess.Popen([str(BIN), "--config", str(cfgfile), "--host", "127.0.0.1", "--port", str(GW_PORT)],
                                                 stdout=open(WORK / "gateway.log", "a"), stderr=subprocess.STDOUT)
        self.procs["fwd"] = subprocess.Popen(["/usr/bin/python3", str(ROOT / "scripts/lan_forward.py"), str(LAN_PORT), str(GW_PORT)],
                                             stdout=open(WORK / "fwd.log", "a"), stderr=subprocess.STDOUT)
        for _ in range(60):
            if http("GET", f"http://{lan_ip()}:{LAN_PORT}/healthz")[0] in (200, 503):
                break
            time.sleep(1)
        self.warm()

    def stop(self):
        for name in ("fwd", "gateway"):
            p = self.procs.pop(name, None)
            if p:
                p.terminate()
                try:
                    p.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    p.kill()
        self.stop_ollama()

    @property
    def base(self):
        return f"http://{lan_ip()}:{LAN_PORT}"

    def loadtest(self, c, n=None, duration=None, think=0, max_tokens=120, stream=True):
        cmd = [sys.executable, str(ROOT / "scripts/loadtest.py"), "--url", self.base, "--key", self.key, "--model", MODEL, "-c", str(c),
               "--max-tokens", str(max_tokens), "--prompt", PROMPT, "--think-time", str(think), "--json"]
        if stream:
            cmd.append("--stream")
        cmd += ["--duration", str(duration)] if duration else ["-n", str(n)]
        out = subprocess.run(cmd, capture_output=True, text=True)
        try:
            return json.loads(out.stdout)
        except Exception:  # noqa: BLE001
            return {"error": (out.stdout + out.stderr)[-300:]}

    def stats(self):
        s, _, b = http("GET", f"{self.base}/stats", key=self.key)
        return json.loads(b) if s == 200 else {}

    def ollama_ps(self):
        s, _, b = http("GET", f"http://127.0.0.1:{OLLAMA_PORT}/api/ps")
        return [dict(name=m["name"], size_gb=round(m["size"] / 1e9, 2), context=m.get("context_length")) for m in json.loads(b).get("models", [])] if s == 200 else []


def save(section, data):
    cur = json.loads(RESULTS.read_text()) if RESULTS.exists() else {}
    cur[section] = data
    cur["generated"] = time.strftime("%Y-%m-%d %H:%M")
    cur["model"] = MODEL
    RESULTS.write_text(json.dumps(cur, indent=1, ensure_ascii=False))


def rss_gb():
    out = subprocess.run(["ps", "-axo", "rss,comm"], capture_output=True, text=True).stdout
    return round(sum(int(l.split(None, 1)[0]) for l in out.splitlines()[1:] if "ollama" in l.lower()) / 1e6, 2)


def thermal():
    out = subprocess.run(["pmset", "-g", "therm"], capture_output=True, text=True).stdout
    m = re.search(r"CPU_Speed_Limit\s*=\s*(\d+)", out)
    return int(m.group(1)) if m else 100


def phase_matrix(a):
    result = {}
    for np_ in (1, 2, 4):
        st = Stack(np_)
        try:
            st.start()
            print(f"== NUM_PARALLEL={np_}  ctx/memory: {st.ollama_ps()}", flush=True)
            row = {"ollama_ps": st.ollama_ps(), "clients": {}}
            for c in (1, 2, 4, 8):
                r = st.loadtest(c, n=24)
                row["clients"][str(c)] = r
                print(f"  c={c}: ok={r.get('ok')}/{r.get('requests')} codes={r.get('codes')} wall={r.get('wall_s')}s latency={r.get('latency_s')} ttft_p50={(r.get('ttft_s') or {}).get('p50')} "
                      f"tok/s per stream={round(r.get('tok_per_s_per_stream', 0), 1)} total={round(r.get('tok_per_s_total', 0), 1)}", flush=True)
            row["ollama_ps_after"] = st.ollama_ps()
            result[f"np{np_}"] = row
        finally:
            st.stop()
        save("matrix", result)
    return result


def phase_soak(a):
    st = Stack(a.np)
    samples, stop = [], threading.Event()
    try:
        st.start()

        def sampler():
            t0 = time.time()
            while not stop.is_set():
                ps = st.ollama_ps()
                s = st.stats()
                samples.append({"t_min": round((time.time() - t0) / 60, 1), "model_gb": sum(m["size_gb"] for m in ps), "ollama_rss_gb": rss_gb(),
                                "cpu_speed_limit": thermal(), "active": s.get("active"), "requests": s.get("requests")})
                stop.wait(30)

        th = threading.Thread(target=sampler, daemon=True)
        th.start()
        r = st.loadtest(a.clients, duration=a.minutes * 60, think=2)
        stop.set()
        th.join(timeout=40)
        save("soak", {"np": a.np, "clients": a.clients, "minutes": a.minutes, "summary": r, "samples": samples, "final_stats": st.stats()})
        print(json.dumps(r)[:600])
        print("samples:", len(samples), "rss first/last:", samples[0]["ollama_rss_gb"], samples[-1]["ollama_rss_gb"], "min cpu limit:", min(x["cpu_speed_limit"] for x in samples))
    finally:
        stop.set()
        st.stop()


def phase_faults(a):
    st = Stack(a.np)
    out = {}
    try:
        st.start()
        # A. Ollama dies and comes back during a 4-client run.
        holder = {}
        t = threading.Thread(target=lambda: holder.update(r=st.loadtest(4, n=48)))
        t.start()
        time.sleep(15)
        killed = time.time()
        st.stop_ollama()
        time.sleep(8)
        st.start_ollama()
        up = time.time()
        recovered = None
        for _ in range(120):
            s, _, _ = http("POST", f"{st.base}/v1/chat/completions", {"model": MODEL, "messages": [{"role": "user", "content": "hi"}], "max_tokens": 4}, key=st.key, timeout=120)
            if s == 200:
                recovered = round(time.time() - up, 1)
                break
            time.sleep(2)
        t.join(timeout=600)
        out["ollama_restart"] = {"down_s": round(up - killed, 1), "first_success_after_restart_s": recovered, "loadtest": holder.get("r"), "stats": st.stats()}
        print("A restart:", json.dumps(out["ollama_restart"])[:500], flush=True)
        # B. clients that disconnect mid-stream must free the slot.
        before = st.stats()
        for _ in range(4):
            subprocess.run(["curl", "-s", "-N", "-m", "1.5", "-H", f"Authorization: Bearer {st.key}", "-H", "Content-Type: application/json", "-d",
                            json.dumps({"model": MODEL, "messages": [{"role": "user", "content": "Write a very long story about a gym."}], "stream": True, "max_tokens": 900}),
                            f"{st.base}/v1/chat/completions"], capture_output=True)
        time.sleep(3)
        after = st.stats()
        t0 = time.time()
        s, _, _ = http("POST", f"{st.base}/v1/chat/completions", {"model": MODEL, "messages": [{"role": "user", "content": "Say OK."}], "max_tokens": 4}, key=st.key, timeout=120)
        out["client_disconnect"] = {"disconnects_before": before.get("clientDisconnects"), "disconnects_after": after.get("clientDisconnects"), "active_after": after.get("active"),
                                    "next_request_status": s, "next_request_s": round(time.time() - t0, 1)}
        print("B disconnect:", out["client_disconnect"], flush=True)
        save("faults", out)
    finally:
        st.stop()


def phase_limits(a):
    st = Stack(1, limits={"rateLimitPerMinute": 30, "rateBurst": 10, "maxConcurrent": 1, "queueSize": 3, "queueTimeoutMs": 90000, "maxRequestsPerKey": 16, "maxTokensCap": 128, "defaultMaxTokens": 64})
    res = []

    def check(name, ok, detail):
        res.append({"check": name, "ok": bool(ok), "detail": detail})
        print(("PASS " if ok else "FAIL ") + name + " - " + str(detail)[:200], flush=True)

    try:
        st.start()
        b, k = st.base, st.key
        chat = lambda body, **kw: http("POST", f"{b}/v1/chat/completions", body, key=k, **kw)  # noqa: E731
        msg = lambda t: [{"role": "user", "content": t}]  # noqa: E731
        s, _, body = http("GET", f"{b}/v1/models", key=k)
        check("models list over LAN", s == 200 and MODEL in body, body[:80])
        s, _, _ = http("GET", f"{b}/v1/models")
        check("no key -> 401", s == 401, s)
        s, _, _ = http("GET", f"{b}/v1/models", key="sk-wrong")
        check("wrong key -> 403", s == 403, s)
        s, _, body = chat({"model": MODEL, "messages": msg("word " * 6000), "max_tokens": 16})
        check("prompt larger than context -> 400 context_length_exceeded", s == 400 and "context_length_exceeded" in body, f"{s} {body[:100]}")
        s, _, body = chat({"model": MODEL, "messages": msg("x" * 70000), "max_tokens": 16})
        check("body > maxBodyBytes -> 413", s == 413, f"{s} {body[:80]}")
        s, _, body = chat({"model": MODEL, "messages": [{"role": "user", "content": "hi"}] * 41, "max_tokens": 16})
        check("too many messages -> 400", s == 400, f"{s} {body[:80]}")
        s, _, body = chat({"model": MODEL, "messages": msg("Write a very long essay about strength training, at least 4000 words, without stopping."), "max_tokens": 99999}, timeout=300)
        used = json.loads(body).get("usage", {}).get("completion_tokens") if s == 200 else None
        check("max_tokens clamped to maxTokensCap (128 in this strict config)", s == 200 and used is not None and 120 <= used <= 128, f"completion_tokens={used} (asked 99999)")
        s, _, body = chat({"model": "not-a-model", "messages": msg("hi")})
        check("unknown model rejected", s in (400, 404), f"{s} {body[:80]}")
        # rate limit: 10 burst, then 429 + Retry-After, then recovery
        codes, retry = [], None
        for _ in range(14):
            s, h, _ = chat({"model": MODEL, "messages": msg("hi"), "max_tokens": 1})
            codes.append(s)
            if s == 429 and retry is None:
                retry = h.get("Retry-After") or h.get("retry-after")
        check("rate limit: 429 after the burst with Retry-After", 429 in codes and retry is not None and codes[:5].count(200) == 5, f"codes={codes} retry-after={retry}")
        time.sleep(float(retry or 5) + 6)
        s, _, _ = chat({"model": MODEL, "messages": msg("hi"), "max_tokens": 1})
        check("rate limit recovers", s == 200, s)
        # queue: maxConcurrent 1 + queueSize 3 -> of 7 simultaneous requests 4 are served (1 running + 3 queued) and 3 get 503 queue_full.
        time.sleep(25)  # let the rate-limit bucket refill first so only the queue limit is exercised
        results = []

        def long_req():
            results.append(chat({"model": MODEL, "messages": msg("Write 200 words about squats."), "max_tokens": 200}, timeout=300)[0])

        ts = [threading.Thread(target=long_req) for _ in range(7)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        check("queue full -> 503 for the surplus, queued requests still succeed", results.count(200) == 4 and results.count(503) == 3, f"statuses={sorted(results)}")
        check("stats counters", st.stats().get("requests", 0) > 10, json.dumps(st.stats())[:200])
        save("limits", res)
    finally:
        st.stop()
    print(f"{sum(r['ok'] for r in res)}/{len(res)} checks passed")


def phase_serve(a):
    """Keeps the service up for manual tests (phone): own Ollama instance with NUM_PARALLEL, gateway and LAN forwarder; Ctrl-C / SIGTERM stops everything."""
    st = Stack(a.np, limits={"rateLimitPerMinute": 60, "rateBurst": 20, "maxConcurrent": a.np, "queueSize": 8, "queueTimeoutMs": 60000, "maxRequestsPerKey": 3,
                             "maxTokensCap": 2048, "defaultMaxTokens": 1024, "requestTimeoutMs": 300000, "streamIdleTimeoutMs": 120000},
                fixed_key=a.key or os.environ.get("GATEWAY_KEY"))
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    try:
        st.start()
        print(f"SERVICE UP  url=http://{lan_ip()}:{LAN_PORT}/  key={st.key}  model={MODEL}  NUM_PARALLEL={a.np}", flush=True)
        stop.wait()
    finally:
        st.stop()
        print("service stopped", flush=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("phase", choices=["matrix", "soak", "faults", "limits", "serve"])
    ap.add_argument("--np", type=int, default=2)
    ap.add_argument("--minutes", type=int, default=15)
    ap.add_argument("--clients", type=int, default=3)
    ap.add_argument("--key", default=None, help="serve: reuse this device key instead of generating a new one")
    args = ap.parse_args()
    WORK.mkdir(exist_ok=True)
    {"matrix": phase_matrix, "soak": phase_soak, "faults": phase_faults, "limits": phase_limits, "serve": phase_serve}[args.phase](args)
