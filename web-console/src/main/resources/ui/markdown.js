/*
 * Minimal self-contained Markdown renderer (no dependencies, no network). Input is UNTRUSTED (model output, corpus text):
 * every piece of text is HTML-escaped before any markup is added; only tags generated here ever reach the output.
 * Supports: headings, bold/italic, inline + fenced code, ordered/unordered (nested) lists, tables, blockquotes,
 * links (http/https/mailto only), horizontal rules, paragraphs and line breaks. Images and raw HTML are NOT supported (shown as text).
 */
(function (root) {
  'use strict';
  var ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return ESC[c]; }); }

  function inline(src) {
    var stash = [];
    var s = esc(String(src).replace(/[\u0001\u0002]/g, ''));
    s = s.replace(/(`+)([\s\S]*?[^`])\1(?!`)/g, function (_, _t, code) {
      stash.push('<code>' + code.trim() + '</code>');
      return '\u0001' + (stash.length - 1) + '\u0002';
    });
    s = s.replace(/(^|[^!])\[([^\]\n]+)\]\(([^()\s]*(?:\([^()\s]*\)[^()\s]*)*)(?:\s+&quot;[^\n]*?&quot;)?\)/g, function (m, pre, text, url) {
      if (!/^(https?:\/\/|mailto:)/i.test(url)) return m;
      stash.push('<a href="' + url + '" target="_blank" rel="noopener noreferrer">' + text + '</a>');
      return pre + '\u0001' + (stash.length - 1) + '\u0002';
    });
    s = s.replace(/\*\*(?=\S)([\s\S]+?)(?<=\S)\*\*|__(?=\S)([\s\S]+?)(?<=\S)__/g, function (_, a, b) { return '<strong>' + (a || b) + '</strong>'; });
    s = s.replace(/(?<![*\w])\*(?=[^\s*])([^*\n]+?)(?<=[^\s*])\*(?![*\w])|(?<![\w_])_(?=[^\s_])([^_\n]+?)(?<=[^\s_])_(?![\w_])/g,
      function (_, a, b) { return '<em>' + (a || b) + '</em>'; });
    return s.replace(/\u0001(\d+)\u0002/g, function (_, i) { return stash[+i]; });
  }

  var FENCE = /^\s*(```+|~~~+)\s*([\w+-]*)\s*$/;
  var HR = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/;
  var HEADING = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
  var ITEM = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/;
  var TABLE_SEP = /^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$/;

  function cells(line) {
    var t = line.trim();
    if (t.charAt(0) === '|') t = t.slice(1);
    if (t.charAt(t.length - 1) === '|' && t.charAt(t.length - 2) !== '\\') t = t.slice(0, -1);
    return t.split(/(?<!\\)\|/).map(function (c) { return c.replace(/\\\|/g, '|').trim(); });
  }

  function isBlockStart(line, next) {
    return FENCE.test(line) || HR.test(line) || HEADING.test(line) || ITEM.test(line) || /^\s{0,3}>/.test(line) ||
      (line.indexOf('|') >= 0 && next !== undefined && TABLE_SEP.test(next) && next.indexOf('-') >= 0);
  }

  function list(items, i, ind) {
    var ord = items[i].ord, tag = ord ? 'ol' : 'ul', h = '<' + tag + '>';
    while (i < items.length && items[i].ind >= ind && (items[i].ind > ind || items[i].ord === ord)) {
      var li = inline(items[i].text);
      i++;
      if (i < items.length && items[i].ind > ind) {
        var r = list(items, i, items[i].ind);
        li += r[0]; i = r[1];
      }
      h += '<li>' + li + '</li>';
    }
    return [h + '</' + tag + '>', i];
  }

  function blocks(text) {
    var lines = String(text).replace(/\r\n?/g, '\n').replace(/\t/g, '    ').split('\n');
    var out = [], i = 0, m;
    while (i < lines.length) {
      var line = lines[i];
      if (!line.trim()) { i++; continue; }
      if ((m = FENCE.exec(line))) {
        var fence = m[1], code = []; i++;
        while (i < lines.length && !(lines[i].trim().indexOf(fence) === 0 && /^[`~]+\s*$/.test(lines[i].trim()))) code.push(lines[i++]);
        i++;
        out.push('<pre><code>' + esc(code.join('\n')) + '</code></pre>');
      } else if (HR.test(line)) {
        out.push('<hr>'); i++;
      } else if ((m = HEADING.exec(line))) {
        var lvl = Math.min(m[1].length + 2, 6);
        out.push('<h' + lvl + '>' + inline(m[2]) + '</h' + lvl + '>'); i++;
      } else if (/^\s{0,3}>/.test(line)) {
        var q = [];
        while (i < lines.length && /^\s{0,3}>/.test(lines[i])) q.push(lines[i++].replace(/^\s{0,3}>\s?/, ''));
        out.push('<blockquote>' + blocks(q.join('\n')) + '</blockquote>');
      } else if (line.indexOf('|') >= 0 && i + 1 < lines.length && TABLE_SEP.test(lines[i + 1]) && lines[i + 1].indexOf('-') >= 0) {
        var head = cells(line), h = '<table><thead><tr>' + head.map(function (c) { return '<th>' + inline(c) + '</th>'; }).join('') + '</tr></thead><tbody>';
        i += 2;
        while (i < lines.length && lines[i].trim() && lines[i].indexOf('|') >= 0) {
          h += '<tr>' + cells(lines[i++]).map(function (c) { return '<td>' + inline(c) + '</td>'; }).join('') + '</tr>';
        }
        out.push(h + '</tbody></table>');
      } else if (ITEM.test(line)) {
        var items = [];
        while (i < lines.length && lines[i].trim()) {
          var im = ITEM.exec(lines[i]);
          if (im) items.push({ ind: im[1].length, ord: /\d/.test(im[2]), text: im[3] });
          else if (items.length && !isBlockStart(lines[i], lines[i + 1])) items[items.length - 1].text += ' ' + lines[i].trim();
          else break;
          i++;
        }
        while (items.length) {
          var consumed = 0, rest = items;
          var r = list(rest, 0, rest[0].ind);
          out.push(r[0]); consumed = r[1];
          items = rest.slice(consumed);
        }
      } else {
        var para = [line];
        i++;
        while (i < lines.length && lines[i].trim() && !isBlockStart(lines[i], lines[i + 1])) para.push(lines[i++]);
        out.push('<p>' + para.map(function (l) { return inline(l.trim()); }).join('<br>') + '</p>');
      }
    }
    return out.join('');
  }

  function renderMarkdown(text) { return blocks(text == null ? '' : text); }
  root.renderMarkdown = renderMarkdown;
  if (typeof module !== 'undefined' && module.exports) module.exports = { renderMarkdown: renderMarkdown };
})(typeof window !== 'undefined' ? window : this);
