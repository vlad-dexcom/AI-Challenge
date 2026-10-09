"""Chunk-level retrieval eval of embedders over synthq.json: expects indexes built with `index --embedder ollama:<model> --out /tmp/embeval/idx_<model>_<prompts|plain>`
and rag/index (Gemini, key from local.properties) as the cloud reference -> chunkeval.json."""
import json,math,sys,time,urllib.request,re,os,glob
from pathlib import Path
HERE=Path(__file__).resolve().parent
R=str(HERE.parents[3])+"/"
Q=json.load(open(HERE/"synthq.json"))
PROMPTS={"embeddinggemma":"task: search result | query: ","qwen3-embedding":"Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: "}
def post(url,b,h=None):
    r=urllib.request.Request(url,json.dumps(b).encode(),{"Content-Type":"application/json",**(h or {})})
    return json.load(urllib.request.urlopen(r,timeout=600))
def norm(v):
    n=math.sqrt(sum(x*x for x in v)) or 1; return [x/n for x in v]
def ollama_embed(model,texts):
    out=[];t=time.time()
    for i in range(0,len(texts),32): out+=post("http://localhost:11434/api/embed",{"model":model,"input":texts[i:i+32]})["embeddings"]
    return [norm(v) for v in out],time.time()-t
def gemini_key():
    for l in open(R+"local.properties"):
        if l.strip().startswith("GEMINI_API_KEY="): return l.split("=",1)[1].strip()
def gemini_embed(texts):
    k=gemini_key();out=[];t=time.time()
    for i in range(0,len(texts),100):
        b=texts[i:i+100]
        j=post("https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents",{"requests":[{"model":"models/gemini-embedding-001","content":{"parts":[{"text":x}]},"taskType":"RETRIEVAL_QUERY","outputDimensionality":768} for x in b]},{"x-goog-api-key":k})
        out+=[e["values"] for e in j["embeddings"]]
    return [norm(v) for v in out],time.time()-t
def evaluate(idxfile,embed):
    idx=json.load(open(idxfile)); ch=idx["chunks"]; ids=[c["chunkId"] for c in ch]; src={c["chunkId"]:c["source"] for c in ch}
    vecs=[c["embedding"] for c in ch]
    res={}
    for lang in("en","ru"):
        keys=[k for k in Q if k in src]; qs=[Q[k][lang] for k in keys]
        qv,secs=embed(qs)
        h={1:0,3:0,5:0};mrr=0;sh1=0;sh3=0
        for k,q in zip(keys,qv):
            sc=sorted(range(len(vecs)),key=lambda i:-sum(a*b for a,b in zip(q,vecs[i])))
            top=[ids[i] for i in sc[:5]]
            r=top.index(k)+1 if k in top else None
            for n in h:
                if r and r<=n: h[n]+=1
            if r: mrr+=1/r
            elif True:
                full=[ids[i] for i in sc]; mrr+=1/(full.index(k)+1)*0  # beyond top5 counts 0 in MRR@5
            s=[src[t] for t in top]; sh1+= s[0]==Q[k]["source"]; sh3+= Q[k]["source"] in s[:3]
        n=len(keys); res[lang]=dict(n=n,h1=h[1]/n,h3=h[3]/n,h5=h[5]/n,mrr=mrr/n,sh1=sh1/n,sh3=sh3/n,qsec=secs/n)
    return res
variants=[]
for d in sorted(glob.glob("/tmp/embeval/idx_*")):
    name=os.path.basename(d)[4:]; model=name.rsplit("_",1)[0].replace("_",":",1) if False else None
    variants.append((name,d))
out=json.load(open(HERE/"chunkeval.json")) if os.path.exists(HERE/"chunkeval.json") else {}
def run(name,idxfile,embed): 
    r=evaluate(idxfile,embed); out[name]=r; print(name,{l:{k:round(v,3) for k,v in x.items()} for l,x in r.items()},flush=True)
for name,d in variants:
    if name in out: continue
    m=re.match(r"(.+)_(prompts|plain)$",name); base,mode=m.group(1),m.group(2)
    model=re.sub(r"^(embeddinggemma-2|qwen3-embedding)_",r"\1:",base)
    pre=next((v for k,v in PROMPTS.items() if model.startswith(k)),"") if mode=="prompts" else ""
    run(name,d+"/structure.json",lambda qs,model=model,pre=pre: ollama_embed(model,[pre+q for q in qs]))
if "gemini" not in out: run("gemini",R+"rag/index/structure.json",gemini_embed)
json.dump(out,open(HERE/"chunkeval.json","w"),indent=1)
