#!/usr/bin/env python3
"""Load test for the AI gateway (asyncio + stdlib). Do NOT point it at a real model while other benchmarks run.

  GATEWAY_KEY=sk-lan-... python3 scripts/loadtest.py --url http://localhost:8088 -c 4 -n 20 --stream
  GATEWAY_KEY=... python3 scripts/loadtest.py -c 2 --duration 900          # soak: keep 2 clients busy for 15 min
"""
import argparse, asyncio, json, os, statistics, sys, time
from urllib.parse import urlparse


def pct(xs, p):
    if not xs:
        return float("nan")
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))]


async def one_request(host, port, key, body, stream, timeout):
    """Returns dict(status, total, ttft, tokens, error). Counts one token per SSE content delta."""
    t0 = time.monotonic()
    res = {"status": 0, "total": 0.0, "ttft": None, "tokens": 0, "error": None, "retry_after": None}
    writer = None
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), 5)
        payload = json.dumps(body).encode()
        head = (f"POST /v1/chat/completions HTTP/1.1\r\nHost: {host}:{port}\r\nAuthorization: Bearer {key}\r\n"
                f"Content-Type: application/json\r\nContent-Length: {len(payload)}\r\nConnection: close\r\n\r\n").encode()
        writer.write(head + payload)
        await writer.drain()

        async def read_all():
            status_line = await reader.readline()
            res["status"] = int(status_line.split()[1])
            headers = {}
            while True:
                line = await reader.readline()
                if line in (b"\r\n", b""):
                    break
                k, _, v = line.decode().partition(":")
                headers[k.strip().lower()] = v.strip()
            res["retry_after"] = headers.get("retry-after")
            chunked = headers.get("transfer-encoding") == "chunked"
            buf = b""
            async for raw in reader:
                buf += raw
                for line in raw.split(b"\n"):
                    if line.startswith(b'data: {"error"'):
                        res["error"] = "stream_error"
                    elif line.startswith(b"data:") and b'"content":"' in line and b'"content":""' not in line:
                        if res["ttft"] is None:
                            res["ttft"] = time.monotonic() - t0
                        res["tokens"] += 1
            if not stream and res["status"] == 200:
                try:
                    text = buf.decode()
                    res["tokens"] = json.loads(text[text.index("{"):text.rindex("}") + 1])["usage"]["completion_tokens"]
                except Exception:
                    pass
                res["ttft"] = time.monotonic() - t0
            del chunked

        await asyncio.wait_for(read_all(), timeout)
    except Exception as e:
        res["error"] = type(e).__name__
    finally:
        res["total"] = time.monotonic() - t0
        if writer:
            writer.close()
    return res


async def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default=os.environ.get("GATEWAY_URL", "http://127.0.0.1:8088"))
    ap.add_argument("--key", default=os.environ.get("GATEWAY_KEY", ""))
    ap.add_argument("--model", default=None)
    ap.add_argument("-c", "--concurrency", type=int, default=2)
    ap.add_argument("-n", "--requests", type=int, default=10, help="total requests (ignored with --duration)")
    ap.add_argument("--duration", type=float, default=0, help="soak: seconds to keep clients busy")
    ap.add_argument("--stream", action="store_true", help="use SSE streaming")
    ap.add_argument("--max-tokens", type=int, default=64)
    ap.add_argument("--prompt", default="Write a short note about home networks.")
    ap.add_argument("--timeout", type=float, default=300)
    ap.add_argument("--think-time", type=float, default=0, help="pause between a client's requests, seconds")
    ap.add_argument("--json", action="store_true", help="print the summary as JSON")
    a = ap.parse_args()
    if not a.key:
        sys.exit("Set GATEWAY_KEY or --key")
    u = urlparse(a.url)
    host, port = u.hostname, u.port or 80
    body = {"messages": [{"role": "user", "content": a.prompt}], "max_tokens": a.max_tokens, "stream": a.stream}
    if a.model:
        body["model"] = a.model

    results, start = [], time.monotonic()
    counter = {"left": a.requests}

    def more():
        if a.duration:
            return time.monotonic() - start < a.duration
        if counter["left"] <= 0:
            return False
        counter["left"] -= 1
        return True

    async def client():
        while more():
            results.append(await one_request(host, port, a.key, body, a.stream, a.timeout))
            if a.think_time:
                await asyncio.sleep(a.think_time)

    await asyncio.gather(*(client() for _ in range(a.concurrency)))
    wall = time.monotonic() - start

    ok = [r for r in results if r["status"] == 200 and not r["error"]]
    codes = {}
    for r in results:
        k = str(r["status"]) if r["status"] and not r["error"] else (r["error"] or "error")
        codes[k] = codes.get(k, 0) + 1
    totals = [r["total"] for r in ok]
    ttfts = [r["ttft"] for r in ok if r["ttft"] is not None]
    per_stream = [r["tokens"] / r["total"] for r in ok if r["total"] > 0 and r["tokens"]]
    total_tokens = sum(r["tokens"] for r in ok)
    summary = {
        "requests": len(results), "ok": len(ok), "codes": codes, "wall_s": round(wall, 2),
        "error_rate": round(1 - len(ok) / len(results), 4) if results else 0,
        "rate_limited_429": codes.get("429", 0), "unavailable_503": codes.get("503", 0),
        "latency_s": {"p50": pct(totals, 50), "p90": pct(totals, 90), "p99": pct(totals, 99)},
        "ttft_s": {"p50": pct(ttfts, 50), "p90": pct(ttfts, 90), "p99": pct(ttfts, 99)},
        "tok_per_s_per_stream": statistics.mean(per_stream) if per_stream else 0,
        "tok_per_s_total": total_tokens / wall if wall else 0,
        "queue_wait_estimate_s": max(0.0, (statistics.mean(ttfts) - min(ttfts))) if len(ttfts) > 1 else 0.0,
    }
    if a.json:
        print(json.dumps(summary, indent=2))
        return
    f = lambda d: " ".join(f"{k}={v:.2f}" for k, v in d.items())
    print(f"requests={summary['requests']} ok={summary['ok']} wall={summary['wall_s']}s codes={codes}")
    print(f"error_rate={summary['error_rate']:.1%}  429={summary['rate_limited_429']}  503={summary['unavailable_503']}")
    print(f"latency_s   {f(summary['latency_s'])}")
    print(f"ttft_s      {f(summary['ttft_s'])}   (queue wait ~ TTFT above the minimum: {summary['queue_wait_estimate_s']:.2f}s avg)")
    print(f"tok/s       per-stream={summary['tok_per_s_per_stream']:.1f}  total={summary['tok_per_s_total']:.1f}")


if __name__ == "__main__":
    asyncio.run(main())
