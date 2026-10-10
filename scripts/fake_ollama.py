#!/usr/bin/env python3
"""Fake Ollama for gateway checks without a model (stdlib only). Default port 11435; the real Ollama owns 11434.

  python3 scripts/fake_ollama.py --tokens 40 --token-delay-ms 25
  curl -XPOST localhost:11435/_control -d '{"mode":"500"}'     # switch behaviour at runtime

Modes: ok | 500 (HTTP error) | drop (close connection mid-stream) | slow (10x slower tokens) | hang (never answers)
"""
import argparse, json, socket, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

state = {"mode": "ok", "tokens": 40, "token_delay_ms": 25, "first_token_ms": 150, "model": "fake:latest"}
counters = {"requests": 0, "active": 0, "completed": 0, "cancelled": 0, "max_active": 0}
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/api/tags":
            self._json(200, {"models": [{"name": state["model"]}]})
        elif self.path == "/_stats":
            self._json(200, {**counters, **state})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if self.path == "/_control":
            state.update(body)
            return self._json(200, state)
        if self.path != "/api/chat":
            return self._json(404, {"error": "not found"})
        self.chat(body)

    def chat(self, req):
        with lock:
            counters["requests"] += 1
            counters["active"] += 1
            counters["max_active"] = max(counters["max_active"], counters["active"])
        try:
            self._chat(req)
        finally:
            with lock:
                counters["active"] -= 1

    def _chat(self, req):
        mode = state["mode"]
        if mode == "500":
            return self._json(500, {"error": "fake upstream failure"})
        if mode == "hang":
            time.sleep(3600)
            return
        n = state["tokens"]
        num_predict = (req.get("options") or {}).get("num_predict")
        if num_predict:
            n = min(n, num_predict)
        scale = 10 if mode == "slow" else 1
        delay = state["token_delay_ms"] / 1000 * scale
        prompt_tokens = max(1, sum(len(m.get("content", "")) for m in req.get("messages", [])) // 4)
        stream = req.get("stream", True)
        t0 = time.time()
        if stream:
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()

        def write(obj):
            data = (json.dumps(obj) + "\n").encode()
            self.wfile.write(f"{len(data):x}\r\n".encode() + data + b"\r\n")
            self.wfile.flush()

        time.sleep(state["first_token_ms"] / 1000 * scale)
        words = []
        try:
            for i in range(n):
                if mode == "drop" and i == n // 2:
                    self.connection.shutdown(socket.SHUT_RDWR)  # close() alone keeps the fd alive via rfile/wfile
                    self.close_connection = True
                    return
                word = f"w{i} "
                words.append(word)
                if stream:
                    write({"model": req.get("model"), "message": {"role": "assistant", "content": word}, "done": False})
                time.sleep(delay)
            elapsed = int((time.time() - t0) * 1e9)
            final = {"model": req.get("model"), "done": True, "done_reason": "stop", "prompt_eval_count": prompt_tokens,
                     "eval_count": n, "eval_duration": elapsed, "total_duration": elapsed}
            if stream:
                write({**final, "message": {"role": "assistant", "content": ""}})
                self.wfile.write(b"0\r\n\r\n")
            else:
                self._json(200, {**final, "message": {"role": "assistant", "content": "".join(words)}})
            with lock:
                counters["completed"] += 1
        except (BrokenPipeError, ConnectionResetError):
            with lock:
                counters["cancelled"] += 1  # the gateway closed the connection: generation would stop here


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=11435)
    p.add_argument("--tokens", type=int, default=40)
    p.add_argument("--token-delay-ms", type=int, default=25)
    p.add_argument("--first-token-ms", type=int, default=150)
    p.add_argument("--mode", default="ok", choices=["ok", "500", "drop", "slow", "hang"])
    p.add_argument("--model", default="fake:latest")
    a = p.parse_args()
    state.update(tokens=a.tokens, token_delay_ms=a.token_delay_ms, first_token_ms=a.first_token_ms, mode=a.mode, model=a.model)
    ThreadingHTTPServer.daemon_threads = True
    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    print(f"fake ollama on http://{a.host}:{a.port} state={state}", flush=True)
    srv.serve_forever()
