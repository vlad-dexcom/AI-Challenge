"""Generates one search question (EN + RU) per chunk of rag/index/structure.json with a local model -> synthq.json."""
import json,re,urllib.request,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent
R=str(HERE.parents[3])+"/"
chunks=json.load(open(R+"rag/index/structure.json"))["chunks"]
OUT=str(HERE/"synthq.json")
try: res=json.load(open(OUT))
except Exception: res={}
M="gemma4:26b-a4b-it-qat"
def chat(p):
    b={"model":M,"messages":[{"role":"user","content":p}],"stream":False,"think":False,"format":"json","options":{"temperature":0.4,"num_predict":400}}
    r=urllib.request.Request("http://localhost:11434/api/chat",json.dumps(b).encode(),{"Content-Type":"application/json"})
    return json.load(urllib.request.urlopen(r,timeout=600))["message"]["content"]
for i,c in enumerate(chunks):
    if c["chunkId"] in res: continue
    p=("You write search questions for a retrieval test. Read the passage and write ONE question a lifting/fitness beginner might ask that this passage answers. "
       "Do NOT copy phrases from the passage, avoid its rare keywords, paraphrase naturally; the question must make sense on its own without the passage. "
       "Return JSON: {\"en\": \"<question in English>\", \"ru\": \"<the same question in natural Russian>\"}.\n\nPassage (section: %s):\n%s"%(c["section"],c["text"][:1800]))
    try:
        j=json.loads(chat(p)); assert j["en"].strip() and j["ru"].strip()
        res[c["chunkId"]]={"source":c["source"],"en":j["en"].strip(),"ru":j["ru"].strip()}
    except Exception as e:
        print("fail",c["chunkId"],e,flush=True); continue
    if i%20==0: json.dump(res,open(OUT,"w"),ensure_ascii=False,indent=1); print(i,len(res),flush=True)
json.dump(res,open(OUT,"w"),ensure_ascii=False,indent=1); print("DONE",len(res))
