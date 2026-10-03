// Renders turnHtml for structured / IDK / error / no-RAG turns with the page's real code; must not throw.
const fs = require('fs');
const html = fs.readFileSync(process.argv[2], 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/g).join('\n').replace(/<\/?script>/g, '');
const esc = script.split('\n').find(l => l.startsWith('const esc='));
const from = script.indexOf('const md=');
const to = script.indexOf('function addMsg');
if (!esc || from < 0 || to < 0) throw new Error('cannot locate page functions');
const window = {};
new Function('window', fs.readFileSync(process.argv[2].replace('index.html', 'markdown.js'), 'utf8'))(window);
const render = new Function('window', esc + '\n' + script.slice(from, to) + '\nreturn {turnHtml, esc, memoryHtml, sessionTurnHtml};')(window);
const q = (status, id) => ({ chunkId: id, text: 'quote', status, resolvedChunkId: id });
const base = { latencyMs: 5, sources: [{ label: 'a.md > S', score: 0.8, chunkId: 'a#1' }], llmCalls: 1,
  chunks: [{ chunkId: 'a#1', source: 'a.md', section: 'S', text: 'text', score: 0.8 }],
  trace: { originalQuery: 'q', searchQuery: 'q', retrieved: [], filtered: [], reranked: [], threshold: 0.65, topKBefore: 10, topKAfter: 4 } };
const ver = { quotes: [q('VERIFIED', 'a#1'), q('NOT_FOUND', 'a#1'), q('TOO_SHORT', 'a#1')], sources: [{ source: { chunkId: 'a#1' }, ok: true, note: 'n' }], attempts: 2, notes: ['x'] };
const turns = [
  { ...base, mode: 'rag', answer: 'ok', structured: { answer: 'ok', quotes: ver.quotes, verification: ver, idk: false, language: 'ru', relatedTopics: [] } },
  { ...base, mode: 'rag', answer: 'idk', sources: [], chunks: [], structured: { answer: 'idk', quotes: [], verification: { quotes: [], sources: [], attempts: 0, notes: [] }, idk: true, idkReason: 'BELOW_THRESHOLD', clarification: 'which?', relatedTopics: ['A', 'B'], language: 'en' } },
  { ...base, mode: 'rag', answer: 'x', structured: { answer: 'x', quotes: [], verification: { quotes: [], sources: [], attempts: 0, notes: [] }, idk: true, idkReason: null, clarification: null, relatedTopics: [] } },
  { ...base, mode: 'no_rag', answer: 'plain', sources: [], chunks: [], trace: null },
  { mode: 'rag', error: 'boom', latencyMs: 1 },
];
for (const t of turns) { const out = render.turnHtml(t); if (typeof out !== 'string' || !out.includes('msg bot')) throw new Error('bad render'); }
for (const v of [1, 0, null, undefined, 2.5]) render.esc(v);
const mem = { goal: 'Plan "3-day" <b>', clarifications: [{ text: 'beginner', turn: 1 }], constraints: { injury: 'knee', diet: 'vegetarian' }, terms: { rpe: 'effort' }, openQuestions: ['sleep?'],
  changes: [{ turn: 3, field: 'constraints', key: 'diet', old: 'omnivore', new: 'vegetarian', kind: 'correct', source: 'llm' }, { turn: 4, field: 'goal', key: null, old: null, new: 'x', kind: 'goal', source: 'user' }] };
const mh = render.memoryHtml(mem);
if (!mh.includes('vegetarian') || mh.includes('<b>"3') || !mh.includes('&quot;3-day&quot;') || !mh.includes('Change history (2)')) throw new Error('memory panel');
render.memoryHtml(null); render.memoryHtml({});
const info = { structured: turns[0].structured, sources: base.sources, chunks: base.chunks, trace: base.trace, memoryDiff: mem.changes, memoryNotes: ['n'], llmCalls: 3, llmMs: 100, wallMs: 200,
  consulted: [{ label: 'b.md > T', chunkId: 'b#1', score: 0.6 }], memoryMode: 'FULL' };
for (const i of [info, { ...info, structured: turns[1].structured, sources: [], chunks: [] }, {}, null]) {
  const out = render.sessionTurnHtml({ role: 'assistant', text: 'a', turn: 1, info: i });
  if (typeof out !== 'string' || !out.includes('msg bot') || !out.endsWith('</div>') || !out.includes('Task memory this turn')) throw new Error('session turn');
}
console.log('turn-html ok');
