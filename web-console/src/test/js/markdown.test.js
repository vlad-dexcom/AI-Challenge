// Run: node web-console/src/test/js/markdown.test.js   (no dependencies)
const assert = require('assert');
const { renderMarkdown: md } = require('../../main/resources/ui/markdown.js');
const has = (s, sub) => assert.ok(s.includes(sub), `expected ${sub} in ${s}`);
const not = (s, sub) => assert.ok(!s.includes(sub), `unexpected ${sub} in ${s}`);

has(md('# T\n## S'), '<h3>T</h3>'); has(md('## S'), '<h4>S</h4>');
has(md('**b** and *i* and _j_ and __k__'), '<strong>b</strong>'); has(md('*i*'), '<em>i</em>');
has(md('a 2 * 3 * 4'), '2 * 3 * 4'); has(md('snake_case_name'), 'snake_case_name');
has(md('use `a<b` here'), '<code>a&lt;b</code>');
has(md('```js\nif (a<b) {}\n**x**\n```'), '<pre><code>if (a&lt;b) {}\n**x**</code></pre>');
has(md('- a\n  - b\n    - c\n- d'), '<ul><li>a<ul><li>b<ul><li>c</li></ul></li></ul></li><li>d</li></ul>');
has(md('1. a\n2. b'), '<ol><li>a</li><li>b</li></ol>');
has(md('- a\n  1. x\n  2. y'), '<li>a<ol><li>x</li><li>y</li></ol></li>');
has(md('| a | b |\n|---|:-:|\n| 1 | **2** |'), '<table><thead><tr><th>a</th><th>b</th></tr></thead><tbody><tr><td>1</td><td><strong>2</strong></td></tr>');
has(md('> quote\n> more'), '<blockquote><p>quote<br>more</p></blockquote>');
has(md('---'), '<hr>');
has(md('l1\nl2\n\np2'), '<p>l1<br>l2</p><p>p2</p>');
has(md('[x](https://a.com/p?q=1&r=2)'), '<a href="https://a.com/p?q=1&amp;r=2" target="_blank" rel="noopener noreferrer">x</a>');
has(md('[m](mailto:a@b.c)'), 'href="mailto:a@b.c"');
has(md('See [1] and [2].'), 'See [1] and [2].'); not(md('See [1]'), '<a');
has(md('a\n\n\n\n'), '<p>a</p>'); assert.strictEqual(md(''), ''); assert.strictEqual(md(null), '');

// XSS payloads: no executable markup may survive
const payloads = [
  '<script>alert(1)</script>', '<img src=x onerror=alert(1)>', '[x](javascript:alert(1))', '[x](JaVaScRiPt:alert(1))',
  '[x](data:text/html,<script>alert(1)</script>)', '[x](vbscript:x)', '[x]( javascript:alert(1))', '![i](https://e.com/a.png)',
  '[x](https://a.com" onmouseover="alert(1))', '`<img onerror=alert(1)>`', '```\n<script>x</script>\n```', '| <b onclick=x> |\n|---|\n| <svg onload=x> |',
  '# <iframe src=javascript:x>', '> <script>x</script>', '- <img src=x onerror=y>', '**<script>x</script>**', '[<img src=x onerror=y>](https://a.com)',
  '[x](&#106;avascript:alert(1))', '<a href="javascript:x">y</a>',
];
for (const p of payloads) {
  const h = md(p);
  assert.ok(!/<(script|img|iframe|svg|b |a href="javascript)/i.test(h.replace(/<a href="https?:[^"]*" target="_blank" rel="noopener noreferrer">/g, '<A>')), 'unsafe tag in: ' + h);
  const tags = h.match(/<[^>]*>/g) || [];
  for (const t of tags) assert.ok(/^<\/?(p|br|h[3-6]|strong|em|code|pre|ul|ol|li|table|thead|tbody|tr|th|td|blockquote|hr)>$|^<a href="(https?:\/\/|mailto:)[^"<>]*" target="_blank" rel="noopener noreferrer">$|^<\/a>$/.test(t), `unexpected tag ${t} from ${p} -> ${h}`);
}
has(md('[x](https://a.com" onmouseover="alert(1))'), '&quot;');
console.log('markdown tests OK');
