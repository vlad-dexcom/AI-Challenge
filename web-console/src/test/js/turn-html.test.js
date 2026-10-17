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

// Day 31: the Dev assistant tab renders answers, IDK answers and MCP calls; model/tool output is escaped.
const hFrom = script.indexOf('function helpHtml');
const hTo = script.indexOf('const helpExamples');
if (hFrom < 0 || hTo < 0) throw new Error('cannot locate helpHtml');
const helpHtml = new Function('window', esc + '\n' + script.slice(script.indexOf('const md='), script.indexOf('function stageTable')) + script.slice(hFrom, hTo) + '\nreturn helpHtml;')(window);
const ok = helpHtml({ answer: 'A <b>x</b>', idk: false, sources: [{ file: 'docs/modules.md', section: 'Modules' }], mcpCalls: [{ tool: 'git_branch', text: 'Current branch: <x>', isError: false }] });
if (!ok.includes('docs/modules.md') || !ok.includes('MCP calls (1)') || ok.includes('<x>') || !ok.includes('&lt;x&gt;')) throw new Error('help html');
if (!helpHtml({ answer: 'I don\'t know', idk: true, sources: [], mcpCalls: [] }).includes('class="idk"')) throw new Error('help idk');
console.log('help-html ok');

// Day 33: the Support tab renders tickets, answers, escalation and CRM calls; customer and model text is escaped.
const sFrom = script.indexOf('function ticketHtml');
const sTo = script.indexOf('const supportExamples');
if (sFrom < 0 || sTo < 0) throw new Error('cannot locate supportHtml');
const support = new Function('window', esc + '\n' + script.slice(script.indexOf('const md='), script.indexOf('function stageTable')) + script.slice(sFrom, sTo) + '\nreturn {ticketHtml, supportHtml};')(window);
const tk = support.ticketHtml({ id: 'T-1', customer: 'A <i>', status: 'open', priority: 'high', subject: 'S', messages: [{ from: 'customer', at: 'x', text: 'hi <script>' }] });
if (!tk.includes('T-1') || tk.includes('<script>') || tk.includes('<i>')) throw new Error('ticket html');
const sr = support.supportHtml({ ticketId: 'T-1', usedTicket: true, answer: 'Cause [1]', idk: false, reply: 'Hello <b>', needsEscalation: true, escalationReason: 'refund <x>',
  sources: [{ file: '02.md', section: 'Error codes' }], searchQuery: 'q <y>', crmCalls: [{ tool: 'get_user', text: 'U <z>', isError: false }] });
if (!sr.includes('Escalate to a human') || !sr.includes('Draft reply') || !sr.includes('CRM calls over MCP (1)') || !sr.includes('02.md') || sr.includes('<x>') || sr.includes('<y>') || sr.includes('<z>')) throw new Error('support html');
const sn = support.supportHtml({ ticketId: null, usedTicket: false, answer: 'I don\'t know', idk: true, reply: '', needsEscalation: false, escalationReason: '', sources: [], searchQuery: 'q', crmCalls: [] });
if (!sn.includes('class="idk"') || !sn.includes('No ticket context') || !sn.includes('No escalation needed') || sn.includes('Draft reply')) throw new Error('support idk');
console.log('support-html ok');
