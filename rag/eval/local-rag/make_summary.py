"""Builds web-console/src/main/resources/ui/optimization-summary.json (the numbers of the Day 29 before/after view) from optimization/*.json.
  python3 rag/eval/local-rag/make_summary.py"""
import json
import statistics as S
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE.parents[2] / "web-console/src/main/resources/ui/optimization-summary.json"
D = HERE / "optimization"
holdout = {q["id"]: q for q in json.loads((HERE / "holdout.json").read_text())["questions"]}


def summarize(runs):
    recs = [r for run in runs for r in run["run"] if "error" not in r]
    inc = [r["scores"] for r in recs if "facts" in r["scores"]]
    ooc = [r["scores"] for r in recs if r["cat"] == "out_of_corpus"]
    part = [r["scores"] for r in recs if r["cat"] == "partial"]
    lat = sorted(r["latency_ms"] for r in recs)
    return {
        "facts": S.mean(s["facts"] for s in inc if s["facts"] is not None),
        "idkInCorpus": S.mean(s["idk"] for s in inc),
        "partial": S.mean(s["facts"] for s in part if s["facts"] is not None) if part else None,
        "outOfCorpusRefused": S.mean(s["idk"] for s in ooc) if ooc else None,
        "quotesVerified": S.mean(bool(s["quotes_ok"]) for s in inc),
        "languageOk": S.mean(r["scores"]["lang_ok"] for r in recs),
        "medianMs": S.median(lat), "p90Ms": lat[max(0, int(0.9 * len(lat)) - 1)],
        "answers": len(recs),
    }


def bench(title, before, after, repeats):
    b, a = json.loads((D / f"{before}.json").read_text()), json.loads((D / f"{after}.json").read_text())
    mem = lambda r: round(sum(m.get("size_gb", 0) for m in r["resources"]["ollama_ps"]), 1)  # noqa: E731
    return {"title": title, "repeats": repeats, "memoryGbBefore": mem(b), "memoryGbAfter": mem(a),
            "sets": {s: {"before": summarize(b["sets"][s]), "after": summarize(a["sets"][s])} for s in ("tuning", "holdout")}}


demo = [
    {"id": "c07", "tag": "до: «не знаю» → после: ответ", "question": "How much protein per kilogram of body weight should an active lifter eat per day, and how should it be spread across the day?"},
    {"id": "freq", "tag": "до: «не знаю» → после: ответ (проверено вживую)", "question": "How many days per week should a beginner lift?"},
    {"id": "ru-c08", "tag": "RU: до: «не знаю» → после: ответ", "question": "В становой тяге штанга уходит вперёд от тела. Как это исправить?"},
    {"id": "h22", "tag": "частичный вопрос", "question": holdout["h22"]["question"]},
    {"id": "h23", "tag": "частичный вопрос", "question": holdout["h23"]["question"]},
    {"id": "h24", "tag": "частичный вопрос", "question": holdout["h24"]["question"]},
    {"id": "c09", "tag": "вне корпуса: оба отказываются (без регресса)", "question": "What is the current men's raw deadlift world record?"},
    {"id": "h07", "tag": "минус: единственная регрессия на hold-out", "question": holdout["h07"]["question"]},
]
summary = {
    "model": "gemma4:26b-a4b-it-qat",
    "note": "Бенчмарк: 16 вопросов tuning (подбор) + 28 вопросов hold-out (не использовались при подборе); те же настройки конвейера; fast — 3 повтора, smart — 2 повтора.",
    "benchmarks": [
        bench("Local-fast: gemma4:26b-a4b-it-qat (MoE, QAT)", "baseline-fast", "final-fast", 3),
        bench("Local-smart: gemma4:31b-mlx (плотная, MLX)", "baseline-smart", "p-local-smart", 2),
    ],
    "demo": demo,
}
OUT.write_text(json.dumps(summary, ensure_ascii=False, indent=1))
print("written", OUT.relative_to(HERE.parents[2]), OUT.stat().st_size, "bytes")
