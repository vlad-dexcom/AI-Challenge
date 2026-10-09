import json,statistics as S
from pathlib import Path
d=json.load(open(Path(__file__).resolve().parent/'results.json'))
def pct(x): return f"{100*x:.0f}%"
rows=[]
for n,e in d.items():
    recs=[r for run in e['runs'] for r in run['run']]
    ok=[r for r in recs if 'error' not in r]
    sc=[r['scores'] for r in ok]
    inc=[s for s in sc if 'facts' in s]
    lat=[r['latency_ms'] for r in ok]
    calls=[r['llm_calls'] for r in ok if r.get('llm_calls') is not None]
    ooc=[s for r,s in zip(ok,sc) if r['cat']=='out_of_corpus']
    answered=[s for s in inc if not s['idk']]
    rows.append(dict(name=n,n=len(recs),err=len(recs)-len(ok),
      facts=S.mean(s['facts'] for s in inc if s['facts'] is not None),
      idk_in=sum(s['idk'] for s in inc)/len(inc),
      idk_ok=S.mean(s['idk_correct'] for s in sc),
      src=S.mean(bool(s['source_ok']) for s in inc if s['source_ok'] is not None),
      quotes=S.mean(bool(s['quotes_ok']) for s in inc),
      lang=S.mean(s['lang_ok'] for s in sc),
      first=S.mean(bool(s['first_try']) for s in sc if s['first_try'] is not None),
      med=S.median(lat),p90=sorted(lat)[int(0.9*len(lat))-1],mean=S.mean(lat),calls=S.mean(calls),
      cold=e.get('cold_start_ms'),run_s=[r['total_s'] for r in e['runs']]))
print(f"{'config':12}{'n':>4}{'err':>4}{'facts':>7}{'IDK on in-corpus':>17}{'IDK ok':>8}{'src':>6}{'quotes':>8}{'lang':>6}{'1st try':>8} | {'med ms':>7}{'p90':>7}{'mean':>7}{'calls':>6}{'cold ms':>8}")
for r in rows: print(f"{r['name']:12}{r['n']:>4}{r['err']:>4}{pct(r['facts']):>7}{pct(r['idk_in']):>17}{pct(r['idk_ok']):>8}{pct(r['src']):>6}{pct(r['quotes']):>8}{pct(r['lang']):>6}{pct(r['first']):>8} | {r['med']:>7.0f}{r['p90']:>7.0f}{r['mean']:>7.0f}{r['calls']:>6.1f}{str(r['cold'] or '-'):>8}")
# stability across repeats
print("\nStability across 3 repeats (same IDK decision AND same fact coverage for a question):")
for n,e in d.items():
    ids=[r['id'] for r in e['runs'][0]['run']]
    same=0;flip=[]
    for i,qid in enumerate(ids):
        vals=[]
        for run in e['runs']:
            r=run['run'][i]
            vals.append(None if 'error' in r else (r['scores']['idk'],round(r['scores'].get('facts') or 0,2)))
        if len(set(vals))==1: same+=1
        else: flip.append(qid)
    print(f"  {n:12} identical on {same}/{len(ids)} questions; varying: {flip}")
# per-question matrix (facts in-corpus / idk)
print("\nPer question (facts avg over repeats, IDK count of 3):")
qs=[r['id'] for r in d['cloud']['runs'][0]['run']]
print(f"{'q':8}"+"".join(f"{n:>16}" for n in d))
for i,q in enumerate(qs):
    line=f"{q:8}"
    for n,e in d.items():
        rs=[run['run'][i] for run in e['runs']]
        okr=[r for r in rs if 'error' not in r]
        f=[r['scores']['facts'] for r in okr if r['scores'].get('facts') is not None]
        idk=sum(r['scores']['idk'] for r in okr)
        line+=f"{(pct(S.mean(f)) if f else '-'):>9} idk{idk}/3"
    print(line)
