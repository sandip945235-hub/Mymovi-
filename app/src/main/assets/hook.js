/* माय मूवी: कार्ड दबाने पर वीडियो को असली Media3 प्लेयर पर भेजता है।
   index.html को छूना नहीं पड़ता। ऐप खुलने पर अपने आप जुड़ जाता है। */
(function () {
  'use strict';
  if (window.__mmHook) return;
  window.__mmHook = true;

  var SHEET = __SHEET__;
  var items = [];
  var lastLoad = 0;
  var skip = false;
  var lastCard = null;

  // ये लिंक वेब-पेज/एम्बेड हैं, वीडियो फ़ाइल नहीं: इन्हें पुराना प्लेयर ही चलाएगा
  var PAGE_RE = /(youtube\.com|youtu\.be|vimeo\.com|dailymotion\.com|facebook\.com|fb\.watch|instagram\.com|tiktok\.com|twitter\.com|\/\/x\.com|drive\.google\.com\/file\/d\/[^\/]+\/preview|\/embed[\/?#=]|\/iframe)/i;
  var HTML_RE = /\.(html?|php|aspx?)(\?|#|$)/i;

  function norm(s) {
    s = String(s || '').toLowerCase();
    try {
      return s.replace(/[^\p{L}\p{M}\p{N}]+/gu, '');
    } catch (e) {
      return s.replace(/[\s"'.,!?:;()\-_]+/g, '');
    }
  }

  function parseCSV(text) {
    text = String(text || '').replace(/^\uFEFF/, '');
    var rows = [], row = [], f = '', q = false, i, c;
    for (i = 0; i < text.length; i++) {
      c = text[i];
      if (q) {
        if (c === '"') { if (text[i + 1] === '"') { f += '"'; i++; } else { q = false; } }
        else { f += c; }
      } else if (c === '"') { q = true; }
      else if (c === ',') { row.push(f); f = ''; }
      else if (c === '\n' || c === '\r') {
        if (c === '\r' && text[i + 1] === '\n') i++;
        row.push(f); f = ''; rows.push(row); row = [];
      } else { f += c; }
    }
    if (f !== '' || row.length) { row.push(f); rows.push(row); }
    return rows;
  }

  function load() {
    if (!SHEET) return;
    var now = Date.now();
    if (now - lastLoad < 8000) return;
    lastLoad = now;
    fetch(SHEET, { cache: 'no-store' })
      .then(function (r) { return r.text(); })
      .then(function (t) {
        var rows = parseCSV(t);
        if (rows.length < 2) return;
        var head = rows[0].map(function (h) {
          return String(h).trim().toLowerCase().replace(/[^a-z]/g, '');
        });
        function idx(names) {
          for (var k = 0; k < head.length; k++) { if (names.indexOf(head[k]) !== -1) return k; }
          return -1;
        }
        var iT = idx(['title', 'name']);
        var iE = idx(['embedlink', 'embed', 'link', 'video', 'videolink', 'url']);
        var iD = idx(['downloadlink', 'download']);
        var iS = idx(['subtitle', 'subtitles', 'sub']);
        function get(r, k) { return k >= 0 && r[k] != null ? String(r[k]).trim() : ''; }
        var out = [];
        rows.slice(1).forEach(function (r) {
          var title = get(r, iT);
          var link = get(r, iE) || get(r, iD);
          if (!title || !link) return;
          out.push({ key: norm(title), title: title, link: link, sub: get(r, iS) });
        });
        items = out;
      })
      .catch(function () {});
  }

  function nativeOk(u) {
    if (!/^https?:\/\//i.test(u)) return false;
    if (PAGE_RE.test(u) || HTML_RE.test(u)) return false;
    return true;
  }

  document.addEventListener('click', function (e) {
    if (skip) return;
    var t = e.target;
    if (!t || !t.closest) return;

    if (t.closest('#refresh')) {
      setTimeout(function () { lastLoad = 0; load(); }, 2500);
      return;
    }

    var card = t.closest('.card');
    if (!card) return;
    if (typeof NativePlayer === 'undefined') return;

    var tEl = card.querySelector('.t');
    var key = norm(tEl ? tEl.textContent : '');
    if (!key) return;

    var found = items.filter(function (it) { return it.key === key; });
    if (!found.length) { load(); return; }

    // एक ही नाम के दो वीडियो अलग-अलग लिंक वाले हों तो गलत वीडियो न चले, पुराना प्लेयर ही चले
    var it = found[0];
    for (var i = 1; i < found.length; i++) {
      if (found[i].link !== it.link) return;
    }
    if (!nativeOk(it.link)) return;

    e.preventDefault();
    e.stopImmediatePropagation();
    lastCard = card;
    NativePlayer.play(it.link, it.title, it.sub || '');
  }, true);

  // नया प्लेयर वीडियो न खोल पाए तो ऐप इसे बुलाकर पुराना प्लेयर चला देता है
  window.__mmFallback = function () {
    if (!lastCard) return;
    skip = true;
    try { lastCard.click(); } catch (err) {}
    skip = false;
  };

  load();
  setTimeout(function () { if (!items.length) { lastLoad = 0; load(); } }, 4000);
})();
