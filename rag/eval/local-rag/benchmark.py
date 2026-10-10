"""Day 28: Cloud vs Hybrid vs Local RAG benchmark through the web console API (quality, speed, stability).

Run from anywhere with the web console up (`./gradlew :web-console:run`), Ollama running and GEMINI_API_KEY set for the cloud rows:
  python3 rag/eval/local-rag/benchmark.py [--resume]      # writes results.json next to this file
  python3 rag/eval/local-rag/report.py                    # prints the tables
"""
import json,re,statistics,subprocess,sys,time,urllib.request,urllib.error
from pathlib import Path
HERE=Path(__file__).resolve().parent
R=str(HERE.parents[2])+"/"
OUT=str(HERE/"results.json")
URL="http://localhost:8080/api/chat"
control=json.load(open(R+"rag/eval/control-questions.json"))["questions"]
RU_EXTRA=[
 ("ru01","Что такое NEAT и почему он падает на диете?","specific",[["neat"],["non-exercise","non exercise"]],[("09-fat-loss-and-body-composition.md","neat")]),
 ("ru02","Кто выиграл чемпионат мира по пауэрлифтингу в этом году?","out_of_corpus",[],[]),
]
TR={"c02":"Какие есть полезные прогрессии для подтягиваний, если я пока не могу сделать строгое подтягивание?",
    "c05":"Как разделить разминку на 10-15 минут на фазы и сколько должна длиться каждая фаза?",
    "c07":"Сколько белка на килограмм массы тела нужно есть активному атлету в день и как распределить его в течение дня?",
    "c08":"В становой тяге штанга уходит вперёд от тела. Как это исправить?"}
Q=[]
for c in control:
    Q.append(dict(id=c["id"],lang="en",question=c["question"],cat=c["category"],must=c["mustContain"],exp=[(e["source"],e["section"].lower()) for e in c["expectedSources"]]))
for i,(cid,txt) in enumerate(TR.items()):
    c=next(x for x in control if x["id"]==cid)
    Q.append(dict(id="ru-"+cid,lang="ru",question=txt,cat=c["category"],must=c["mustContain"],exp=[(e["source"],e["section"].lower()) for e in c["expectedSources"]]))
for qid,txt,cat,must,exp in RU_EXTRA:
    Q.append(dict(id=qid,lang="ru",question=txt,cat=cat,must=must,exp=exp))
CONFIGS=[("cloud","gemini",None),("hybrid","hybrid",None),("local-fast","ollama","gemma4:26b-a4b-it-qat"),("local-smart","ollama","gemma4:31b-mlx")]
def call(provider,model,q,mode="rag",timeout=900):
    b={"provider":provider,"question":q,"mode":mode,"filter":True,"rerank":True,"rewrite":True,"strategy":"structure","topK":4,"topKBefore":10,"threshold":0.65,"citations":True}
    if model: b["model"]=model
    r=urllib.request.Request(URL,json.dumps(b).encode(),{"Content-Type":"application/json"})
    t=time.time()
    try:
        j=json.load(urllib.request.urlopen(r,timeout=timeout)); wall=time.time()-t
        return j["results"][0],wall,None
    except urllib.error.HTTPError as e:
        return None,time.time()-t,f"HTTP {e.code}: {e.read().decode()[:150]}"
    except Exception as e:
        return None,time.time()-t,str(e)[:150]
def cyr(s):
    l=[c for c in s if c.isalpha()]; return (sum("а"<=c.lower()<="я" or c.lower()=="ё" for c in l)/len(l)) if l else 0
def score(q,t):
    st=t.get("structured") or {}
    quotes=st.get("quotes") or []
    qtext=" ".join(x.get("text","") for x in quotes)
    ans=t.get("answer") or ""
    hay=(ans+" "+qtext).lower()
    idk=bool(st.get("idk"))
    ooc=q["cat"]=="out_of_corpus"
    s={"idk":idk,"idk_correct":idk==ooc}
    if not ooc:
        groups=q["must"]
        s["facts"]=(sum(any(k.lower() in hay for k in g) for g in groups)/len(groups)) if groups else None
        labels=" | ".join(x["label"].lower() for x in (t.get("sources") or []))
        s["source_ok"]=(not idk) and all(f.lower() in labels and sec in labels for f,sec in q["exp"]) if q["exp"] else None
        s["quotes_ok"]=(not idk) and len(quotes)>0 and all(x["status"] in("VERIFIED","REATTRIBUTED") for x in quotes)
    sh=cyr(ans)
    s["lang_ok"]=(sh>=0.5) if q["lang"]=="ru" else (sh<0.2)
    att=(st.get("verification") or {}).get("attempts")
    s["first_try"]=(att==1) if att is not None else None
    return s
if __name__=="__main__":
    res=json.load(open(OUT)) if len(sys.argv)>1 and sys.argv[1]=="--resume" else {}
    REPEATS=3
    for name,prov,model in CONFIGS:
        if name in res and len(res[name]["runs"])>=REPEATS: continue
        entry=res.setdefault(name,{"provider":prov,"model":model,"runs":[],"cold_start_ms":None})
        if prov=="ollama":
            subprocess.run(["ollama","stop","gemma4:26b-a4b-it-qat"],capture_output=True); subprocess.run(["ollama","stop","gemma4:31b-mlx"],capture_output=True); time.sleep(3)
            if entry["cold_start_ms"] is None:
                t,wall,err=call(prov,model,"Say hi in one word.",mode="no_rag")
                entry["cold_start_ms"]=round(wall*1000); print(name,"cold start",entry["cold_start_ms"],"ms",err or "",flush=True)
        else:
            call(prov,model,"Say hi in one word.",mode="no_rag")
        for rep in range(len(entry["runs"]),REPEATS):
            run=[]; t0=time.time()
            for q in Q:
                t,wall,err=call(prov,model,q["question"])
                rec={"id":q["id"],"lang":q["lang"],"cat":q["cat"],"wall_ms":round(wall*1000)}
                if t is None or t.get("error"):
                    rec["error"]=err or t.get("error")
                else:
                    rec.update(latency_ms=t["latencyMs"],llm_calls=t.get("llmCalls"),answer=t.get("answer"),scores=score(q,t),
                               sources=[x["label"] for x in t.get("sources") or []],quotes=[x["text"][:120] for x in (t.get("structured") or {}).get("quotes",[])])
                run.append(rec); print(name,rep,q["id"],rec.get("latency_ms"),rec.get("error") or rec["scores"],flush=True)
            entry["runs"].append({"run":run,"total_s":round(time.time()-t0,1)})
            json.dump(res,open(OUT,"w"),ensure_ascii=False,indent=1)
    print("ALLDONE",flush=True)
