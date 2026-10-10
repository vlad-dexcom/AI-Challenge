"""Prints quality / speed / resources / call-profile tables from optimization/<config>.json.
  python3 rag/eval/local-rag/optimize_report.py [config ...] [--profile]   (default: all configs)"""
import json
import statistics as S
import sys
from pathlib import Path

D = Path(__file__).resolve().parent / "optimization"


def pct(x):
    return "-" if x is None else f"{100 * x:.0f}%"


def summarize(runs):
    recs = [r for run in runs for r in run["run"]]
    ok = [r for r in recs if "error" not in r]
    sc = [r["scores"] for r in ok]
    inc = [s for s in sc if "facts" in s]
    lat = sorted(r["latency_ms"] for r in ok)
    ooc = [s for r, s in zip(ok, sc) if r["cat"] == "out_of_corpus"]
    part = [s for r, s in zip(ok, sc) if r["cat"] == "partial"]
    return dict(
        n=len(recs), err=len(recs) - len(ok),
        facts=S.mean(s["facts"] for s in inc if s["facts"] is not None) if inc else None,
        idk_in=S.mean(s["idk"] for s in inc) if inc else None,
        ooc=S.mean(s["idk"] for s in ooc) if ooc else None,
        partial=S.mean(s["facts"] for s in part if s["facts"] is not None) if part else None,
        quotes=S.mean(bool(s["quotes_ok"]) for s in inc) if inc else None,
        lang=S.mean(s["lang_ok"] for s in sc) if sc else None,
        med=S.median(lat) if lat else None, p90=lat[max(0, int(0.9 * len(lat)) - 1)] if lat else None,
        calls=S.mean(r["llm_calls"] for r in ok if r.get("llm_calls") is not None) if ok else None,
    )


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    names = args or sorted(p.stem for p in D.glob("*.json") if p.stem != "configs")
    print(f"{'config':26}{'set':8}{'n':>4}{'err':>4}{'facts':>7}{'IDK in':>8}{'OOC idk':>9}{'partial':>9}{'quotes':>8}{'lang':>6} | {'med ms':>7}{'p90':>7}{'calls':>6}{'cold ms':>8}{'mem GB':>8}")
    for n in names:
        r = json.loads((D / f"{n}.json").read_text())
        mem = sum(m.get("size_gb", 0) for m in r["resources"]["ollama_ps"])
        for sname, runs in r["sets"].items():
            s = summarize(runs)
            print(f"{n:26}{sname:8}{s['n']:>4}{s['err']:>4}{pct(s['facts']):>7}{pct(s['idk_in']):>8}{pct(s['ooc']):>9}{pct(s['partial']):>9}{pct(s['quotes']):>8}{pct(s['lang']):>6} | "
                  f"{s['med'] or 0:>7.0f}{s['p90'] or 0:>7.0f}{s['calls'] or 0:>6.1f}{r['cold_start_ms']:>8}{mem:>8.1f}")
    if "--profile" in sys.argv:
        for n in names:
            r = json.loads((D / f"{n}.json").read_text())
            for sname, runs in r["sets"].items():
                print(f"\n== call profile {n} / {sname} (first repeat)")
                print(f"{'purpose':10}{'calls':>6}{'prompt avg':>11}{'p90':>6}{'max':>6}{'compl avg':>10}{'p90':>6}{'max':>6}{'gen tok/s':>10}{'prompt tok/s':>13}{'avg ms':>8}")
                for p in runs[0]["call_stats"]:
                    print(f"{p['purpose']:10}{p['calls']:>6}{p['promptTokensAvg']:>11.0f}{p['promptTokensP90']:>6}{p['promptTokensMax']:>6}{p['completionTokensAvg']:>10.0f}{p['completionTokensP90']:>6}{p['completionTokensMax']:>6}{p['generationTokPerSec']:>10.1f}{p['promptTokPerSec']:>13.0f}{p['totalMillisAvg']:>8.0f}")


main()
