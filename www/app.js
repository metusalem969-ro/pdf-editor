'use strict';

// ── PDF.js worker (local, offline-safe) ─────────────────────────────────────
pdfjsLib.GlobalWorkerOptions.workerSrc = 'lib/pdf.worker.min.js';

// ── State ───────────────────────────────────────────────────────────────────
let pdfDoc = null;
let pageNum = 1;
let numPages = 0;
let scale = 1;
let outputDpr = 1;
let pinchPreviewScale = null;
let curTool = 'scroll';
let isDrawing = false;
let activePointerId = null;
let annData = {};
let liveStroke = null;
let snapBase = null;
let renderTask = null;
let currentFileKey = null;
let currentFileName = '';
let notePendingPos = null;
let viewMode = 'none';
let currentWebUrl = '';
let webBlobUrl = null;
let localHtmlSource = '';
let storedHtmlSource = '';
let webIsLocal = false;
let webActiveFrame = null;

const THUMB_MAX = 24;
const thumbCache = new Map();
const thumbOrder = [];

const PAGE_CACHE_MAX = 8;
const pagePdfCache = new Map();
const pageCacheOrder = [];
const pageSlots = new Map();
let pagesFlow = null;
let pageObserver = null;
let scrollSyncing = false;
let scrollTimer = null;
let activeDrawSlot = null;

const pagesFlowEl = document.getElementById('pages-flow');
const viewer = document.getElementById('viewer');
const sidebar = document.getElementById('sidebar');
const colorEl = document.getElementById('color-inp');
const sizeEl = document.getElementById('size-inp');

pagesFlow = pagesFlowEl;

// Pinch zoom state
let pinchStartDist = 0;
let pinchStartScale = 1;
let pinchActive = false;
let pinchPointers = new Map();

// ── Theme ───────────────────────────────────────────────────────────────────
const THEME_KEY = 'pdf-editor-theme-v2';

function updateThemeBtn() {
  const btn = document.getElementById('btn-theme');
  if (!btn) return;
  const dark = document.documentElement.getAttribute('data-theme') === 'dark';
  btn.textContent = dark ? '☀️' : '🌙';
  btn.title = dark ? 'Mod luminos' : 'Mod întunecat';
}

function loadTheme() {
  const t = localStorage.getItem(THEME_KEY) || 'light';
  document.documentElement.setAttribute('data-theme', t);
  updateThemeBtn();
}

function toggleTheme() {
  const next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', next);
  localStorage.setItem(THEME_KEY, next);
  updateThemeBtn();
  showToast(next === 'dark' ? '🌙 Mod întunecat' : '☀️ Mod luminos');
}

loadTheme();

// ── Helpers ─────────────────────────────────────────────────────────────────
const px2doc = v => v / renderScale();
const doc2px = (v, sc) => v * (sc || renderScale());

function getColor() { return colorEl.value; }
function getSize() { return Number(sizeEl.value); }

function hexRGB(hex) {
  const n = parseInt(hex.replace('#', ''), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

async function hashBuffer(buf) {
  if (crypto.subtle) {
    const h = await crypto.subtle.digest('SHA-256', buf);
    return Array.from(new Uint8Array(h)).map(b => b.toString(16).padStart(2, '0')).join('').slice(0, 16);
  }
  return String(buf.byteLength);
}

let toastTimer;
function showToast(msg, dur = 2500) {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), dur);
}

function setLoading(on, msg = 'Se procesează...') {
  document.getElementById('loading-msg').textContent = msg;
  document.getElementById('loading').classList.toggle('on', on);
}

function cancelRender() {
  if (renderTask) {
    try { renderTask.cancel(); } catch (_) {}
    renderTask = null;
  }
}

function clearWebContent() {
  const visual = document.getElementById('web-visual');
  const sourceEl = document.getElementById('web-source');
  if (visual) {
    visual.innerHTML = '';
    visual.classList.remove('hidden');
  }
  if (sourceEl) {
    sourceEl.textContent = '';
    sourceEl.classList.remove('on');
  }
  webActiveFrame = null;
  if (webBlobUrl) {
    URL.revokeObjectURL(webBlobUrl);
    webBlobUrl = null;
  }
}

function destroyWeb() {
  const overlay = document.getElementById('web-overlay');
  clearWebContent();
  currentWebUrl = '';
  localHtmlSource = '';
  storedHtmlSource = '';
  webIsLocal = false;
  if (overlay) overlay.classList.remove('on');
  document.documentElement.classList.remove('web-active');
  document.body.classList.remove('web-mode');
}

function setViewerMode(mode) {
  viewMode = mode;
  const dropzone = document.getElementById('dropzone');
  const pdfOnly = ['btn-prev', 'btn-next', 'btn-thumbs', 'page-info', 'page-jump', 'btn-zoom-out', 'btn-zoom-in', 'btn-fit', 'btn-exp'];
  const bottombar = document.getElementById('bottombar');
  const sidebarEl = document.getElementById('sidebar');

  if (mode === 'none') {
    dropzone.style.display = 'flex';
    pagesFlow.style.display = 'none';
    destroyWeb();
  } else {
    dropzone.style.display = 'none';
  }

  pagesFlow.style.display = mode === 'pdf' ? 'block' : 'none';
  pdfOnly.forEach(id => {
    const el = document.getElementById(id);
    if (el) el.style.display = mode === 'pdf' ? '' : 'none';
  });
  if (bottombar) bottombar.style.display = mode === 'pdf' ? '' : 'none';
  if (sidebarEl) sidebarEl.style.display = mode === 'pdf' ? '' : 'none';
}

function destroyPdf() {
  cancelRender();
  if (pdfDoc) {
    try { pdfDoc.destroy(); } catch (_) {}
    pdfDoc = null;
  }
  thumbCache.forEach(entry => {
    if (entry.canvas) entry.canvas.width = 0;
  });
  thumbCache.clear();
  thumbOrder.length = 0;
  pagePdfCache.forEach(e => { if (e.bitmap?.close) e.bitmap.close(); });
  pagePdfCache.clear();
  pageCacheOrder.length = 0;
  pageSlots.clear();
  if (pageObserver) { pageObserver.disconnect(); pageObserver = null; }
  if (pagesFlow) pagesFlow.innerHTML = '';
  sidebar.innerHTML = '';
}

function pageCacheKey(num) {
  return num + '@' + scale.toFixed(4) + '@' + outputDpr.toFixed(2);
}

function evictPageCache() {
  while (pageCacheOrder.length > PAGE_CACHE_MAX) {
    const k = pageCacheOrder.shift();
    const e = pagePdfCache.get(k);
    if (e?.bitmap?.close) e.bitmap.close();
    pagePdfCache.delete(k);
  }
}

function clearPageCache() {
  pagePdfCache.forEach(e => { if (e?.bitmap?.close) e.bitmap.close(); });
  pagePdfCache.clear();
  pageCacheOrder.length = 0;
}

function preloadAdjacentPages() {
  if (!pdfDoc) return;
  [pageNum - 1, pageNum + 1].forEach(n => {
    if (n >= 1 && n <= numPages) preloadPagePdf(n);
  });
}

async function preloadPagePdf(num) {
  outputDpr = Math.min(window.devicePixelRatio || 1, 2.5);
  const key = pageCacheKey(num);
  if (pagePdfCache.has(key)) return;
  try {
    const page = await pdfDoc.getPage(num);
    const vp = page.getViewport({ scale: renderScale() });
    const c = document.createElement('canvas');
    c.width = Math.floor(vp.width);
    c.height = Math.floor(vp.height);
    await page.render({ canvasContext: c.getContext('2d', { alpha: false }), viewport: vp }).promise;
    try { page.cleanup(); } catch (_) {}
    let bitmap;
    try { bitmap = await createImageBitmap(c); } catch (_) { bitmap = null; }
    pagePdfCache.set(key, { bitmap, fallback: bitmap ? null : c });
    pageCacheOrder.push(key);
    evictPageCache();
  } catch (_) {}
}

function animatePageEnter() {}
function animatePageExit() {}

function buildPagesFlow() {
  pagesFlow.innerHTML = '';
  pageSlots.clear();
  for (let i = 1; i <= numPages; i++) {
    const frame = document.createElement('div');
    frame.className = 'page-frame';
    frame.dataset.page = String(i);
    frame.innerHTML = '<div class="page-inner"><div class="page-placeholder"></div><canvas class="pdf-layer" style="display:none"></canvas><canvas class="ann-layer" style="display:none"></canvas></div>';
    pagesFlow.appendChild(frame);
    const inner = frame.querySelector('.page-inner');
    const pdfC = frame.querySelector('.pdf-layer');
    const annC = frame.querySelector('.ann-layer');
    pageSlots.set(i, {
      frame, inner, pdfCvs: pdfC, annCvs: annC,
      placeholder: frame.querySelector('.page-placeholder'),
      pdfCtx: pdfC.getContext('2d', { alpha: false }),
      annCtx: annC.getContext('2d'),
      rendered: false, rendering: false
    });
  }
  setupPageObserver();
}

function setupPageObserver() {
  if (pageObserver) pageObserver.disconnect();
  pageObserver = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      const n = parseInt(entry.target.dataset.page, 10);
      if (entry.isIntersecting) {
        renderPageSlot(n);
        if (n > 1) renderPageSlot(n - 1);
        if (n < numPages) renderPageSlot(n + 1);
      }
    });
  }, { root: viewer, rootMargin: '240px 0px', threshold: 0.01 });
  pageSlots.forEach(slot => pageObserver.observe(slot.frame));
}

function updatePageFromScroll() {
  if (scrollSyncing || !pdfDoc) return;
  const mid = viewer.scrollTop + viewer.clientHeight * 0.42;
  let best = pageNum, bestDist = Infinity;
  pageSlots.forEach((slot, n) => {
    const top = slot.frame.offsetTop;
    const h = slot.frame.offsetHeight || 1;
    const dist = Math.abs(top + h * 0.35 - mid);
    if (dist < bestDist) { bestDist = dist; best = n; }
  });
  if (best !== pageNum) {
    pageNum = best;
    updateNav();
    highlightThumb(pageNum);
    scheduleThumbRender(pageNum);
  }
}

viewer.addEventListener('scroll', () => {
  clearTimeout(scrollTimer);
  scrollTimer = setTimeout(updatePageFromScroll, 60);
}, { passive: true });

function scrollToPage(n, smooth) {
  const slot = pageSlots.get(n);
  if (!slot) return;
  scrollSyncing = true;
  pageNum = n;
  updateNav();
  highlightThumb(n);
  renderPageSlot(n);
  slot.frame.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'start' });
  setTimeout(() => { scrollSyncing = false; }, smooth ? 450 : 60);
}

async function renderVisiblePages() {
  const top = viewer.scrollTop;
  const bottom = top + viewer.clientHeight;
  const tasks = [];
  pageSlots.forEach((slot, n) => {
    const y = slot.frame.offsetTop;
    const h = slot.frame.offsetHeight || 500;
    if (y + h > top - 300 && y < bottom + 300) tasks.push(renderPageSlot(n));
  });
  await Promise.all(tasks);
}

// ── Annotations persistence ─────────────────────────────────────────────────
function saveAnnotations() {
  if (!currentFileKey) return;
  try {
    localStorage.setItem('pdf-ann:' + currentFileKey, JSON.stringify(annData));
  } catch (e) {
    showToast('⚠️ Nu s-au putut salva adnotările');
  }
}

function loadAnnotations() {
  if (!currentFileKey) return;
  try {
    const raw = localStorage.getItem('pdf-ann:' + currentFileKey);
    if (raw) {
      annData = JSON.parse(raw);
      showToast('📎 Adnotări restaurate');
    }
  } catch (_) {
    annData = {};
  }
}

function persistStroke() {
  saveAnnotations();
}

// ── File loading ────────────────────────────────────────────────────────────
document.getElementById('file-inp').addEventListener('change', e => {
  const f = e.target.files[0];
  if (f) openFile(f);
  e.target.value = '';
});

function isPdfBuffer(buf) {
  const u = new Uint8Array(buf);
  return u.length >= 5 && u[0] === 0x25 && u[1] === 0x50 && u[2] === 0x44 && u[3] === 0x46;
}

function isHtmlBuffer(buf) {
  const u = new Uint8Array(buf.slice(0, Math.min(buf.byteLength, 512)));
  const s = new TextDecoder('utf-8', { fatal: false }).decode(u).trimStart().toLowerCase();
  return s.startsWith('<!doctype html') || s.startsWith('<html') || s.startsWith('<head') || s.startsWith('<body');
}

function setWebTab(tab) {
  const visual = document.getElementById('web-visual');
  const sourceEl = document.getElementById('web-source');
  document.querySelectorAll('.web-tab').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.tab === tab);
  });
  if (tab === 'code') {
    visual.classList.add('hidden');
    sourceEl.classList.add('on');
    if (!sourceEl.textContent) {
      if (storedHtmlSource) {
        sourceEl.textContent = storedHtmlSource;
      } else if (!webIsLocal) {
        sourceEl.textContent = 'Codul sursă nu poate fi descărcat direct (limitare server/CORS).\n\nPagina se vede în tab-ul „Pagină”.';
      } else {
        sourceEl.textContent = '(Fișier gol)';
      }
    }
  } else {
    visual.classList.remove('hidden');
    sourceEl.classList.remove('on');
    if (!webActiveFrame || !webActiveFrame.parentNode) {
      renderWebVisual({ local: webIsLocal, url: currentWebUrl, source: localHtmlSource || storedHtmlSource });
    }
  }
}

function prepareLocalHtml(html) {
  const inject = '<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">';
  const closeHead = '<' + '/head>';
  if (/<meta[^>]+name=["']viewport/i.test(html)) return html;
  if (/<head[^>]*>/i.test(html)) {
    return html.replace(/<head([^>]*)>/i, (m, attrs) => '<head' + attrs + '>' + inject);
  }
  if (/<html[^>]*>/i.test(html)) {
    return html.replace(/<html([^>]*)>/i, (m, attrs) => '<html' + attrs + '><head>' + inject + closeHead);
  }
  return inject + html;
}

function writeHtmlToFrame(frame, html) {
  try {
    const doc = frame.contentDocument || frame.contentWindow?.document;
    if (!doc) return false;
    doc.open();
    doc.write(html);
    doc.close();
    return true;
  } catch (_) {
    return false;
  }
}

function renderWebVisual({ local, url, source }) {
  const visual = document.getElementById('web-visual');
  if (!visual) return null;
  visual.innerHTML = '';
  visual.classList.remove('hidden');

  const frame = document.createElement('iframe');
  frame.setAttribute('title', 'Pagină web');
  visual.appendChild(frame);
  webActiveFrame = frame;

  if (local && source) {
    const html = prepareLocalHtml(source);
    frame.srcdoc = html;
    setTimeout(() => {
      try {
        const doc = frame.contentDocument;
        const body = doc && doc.body;
        const empty = !body || (!body.textContent.trim() && !body.querySelector('img,canvas,svg,table,iframe'));
        if (empty) writeHtmlToFrame(frame, html);
      } catch (_) {
        try { frame.srcdoc = html; } catch (e2) {
          frame.src = 'data:text/html;charset=utf-8,' + encodeURIComponent(html);
        }
      }
    }, 250);
    return frame;
  }

  if (url) frame.src = url;
  return frame;
}

async function openWebPage({ url, source, name, fetchOk = true, local = false }) {
  destroyPdf();
  clearWebContent();
  setViewerMode('web');

  const overlay = document.getElementById('web-overlay');
  const sourceEl = document.getElementById('web-source');
  const urlDisplay = document.getElementById('web-url-display');

  currentFileName = name || url;
  currentWebUrl = url;
  webIsLocal = !!local;
  localHtmlSource = local && source ? source : '';
  storedHtmlSource = source || '';
  overlay.classList.add('on');
  document.documentElement.classList.add('web-active');
  document.body.classList.add('web-mode');

  urlDisplay.textContent = name || url;
  urlDisplay.title = local ? (name || 'Fișier local') : url;

  if (sourceEl) {
    sourceEl.textContent = '';
    sourceEl.classList.remove('on');
  }

  document.querySelectorAll('.web-tab').forEach((btn, i) => btn.classList.toggle('active', i === 0));
  renderWebVisual({ local, url, source: localHtmlSource || storedHtmlSource });
  if (local && source && source.includes('pdfjsLib')) {
    showToast('⚠️ Acesta e fișierul aplicației — pentru test folosește un HTML simplu');
  } else {
    showToast(local ? '📄 ' + (name || 'HTML local') : '🌐 ' + (name || 'Pagină web'));
  }
}

function openUrlDialog() {
  document.getElementById('url-inp').value = '';
  document.getElementById('url-dialog').classList.add('on');
  setTimeout(() => document.getElementById('url-inp').focus(), 100);
}

function bindTap(el, fn) {
  if (!el) return;
  const run = e => { e.preventDefault(); e.stopPropagation(); fn(e); };
  el.addEventListener('click', run);
  el.addEventListener('touchend', run, { passive: false });
}

bindTap(document.getElementById('btn-url'), openUrlDialog);
bindTap(document.getElementById('dz-url-btn'), openUrlDialog);

viewer.addEventListener('dragover', e => e.preventDefault());
viewer.addEventListener('drop', e => {
  e.preventDefault();
  const f = [...e.dataTransfer.files].find(f => {
    const n = f.name.toLowerCase();
    return f.type === 'application/pdf' || n.endsWith('.pdf') || f.type === 'text/html' || n.endsWith('.html') || n.endsWith('.htm') || f.type === 'text/plain' || n.endsWith('.txt');
  });
  if (f) openFile(f);
});

async function openFile(file) {
  const buf = await file.arrayBuffer();
  const lower = file.name.toLowerCase();
  if (isPdfBuffer(buf)) {
    await loadPdfBuffer(buf, file.name);
  } else if (lower.endsWith('.txt') || file.type === 'text/plain') {
    const text = new TextDecoder('utf-8').decode(buf);
    openEditor({ content: text, name: file.name, format: 'txt' });
  } else if (lower.endsWith('.html') || lower.endsWith('.htm') || file.type === 'text/html' || isHtmlBuffer(buf)) {
    const text = new TextDecoder('utf-8').decode(buf);
    await openWebPage({ url: file.name, source: text, name: file.name, fetchOk: true, local: true });
  } else {
    showToast('❌ Format nesuportat — PDF, TXT sau HTML');
  }
}

async function openFromUrl(url) {
  url = (url || '').trim();
  if (!url) return;
  if (!/^https?:\/\//i.test(url)) url = 'https://' + url;
  setLoading(true, 'Se descarcă…');
  try {
    let name = decodeURIComponent((url.split('/').pop() || 'pagina').split('?')[0]) || 'pagina';

    let res, buf, text = '', ct = '';
    try {
      res = await fetch(url);
      if (!res.ok) throw new Error('HTTP ' + res.status);
      ct = (res.headers.get('content-type') || '').toLowerCase();
      buf = await res.arrayBuffer();
    } catch (fetchErr) {
      addRecent({ name, url, type: 'web' });
      await openWebPage({ url, source: '', name, fetchOk: false });
      showToast('🌐 Pagină deschisă (cod sursă limitat)');
      setLoading(false);
      return;
    }

    if (isPdfBuffer(buf)) {
      if (!name.toLowerCase().endsWith('.pdf')) name += '.pdf';
      addRecent({ name, url, type: 'pdf' });
      await loadPdfBuffer(buf, name);
      showToast('✅ PDF descărcat');
    } else if (ct.includes('text/html') || ct.includes('application/xhtml') || isHtmlBuffer(buf)) {
      text = new TextDecoder('utf-8').decode(buf);
      if (!name.includes('.')) name += '.html';
      addRecent({ name, url, type: 'web' });
      await openWebPage({ url, source: text, name, fetchOk: true });
      showToast('✅ Pagină web + cod sursă');
    } else {
      text = new TextDecoder('utf-8', { fatal: false }).decode(buf);
      addRecent({ name, url, type: 'web' });
      await openWebPage({ url, source: text.slice(0, 500000), name, fetchOk: true });
      showToast('🌐 Conținut web deschis');
    }
  } catch (err) {
    console.error(err);
    showToast('❌ ' + (err.message || 'Nu s-a putut deschide'));
  }
  setLoading(false);
}

async function loadPdfBuffer(buf, name) {
  if (!isPdfBuffer(buf)) {
    const text = new TextDecoder('utf-8', { fatal: false }).decode(buf);
    if (isHtmlBuffer(buf)) {
      await openWebPage({ url: name.replace(/\.pdf$/i, '.html'), source: text, name: name.replace(/\.pdf$/i, '.html'), fetchOk: true, local: true });
      return;
    }
    showToast('❌ Fișier invalid — PDF sau HTML');
    return;
  }
  setLoading(true, 'Se încarcă ' + name + '…');
  try {
    destroyWeb();
    destroyPdf();
    setViewerMode('pdf');
    currentFileKey = await hashBuffer(buf);
    currentFileName = name;
    annData = {};

    pdfDoc = await pdfjsLib.getDocument({ data: new Uint8Array(buf) }).promise;

    numPages = pdfDoc.numPages;
    pageNum = 1;
    loadAnnotations();

    document.getElementById('dropzone').style.display = 'none';
    pagesFlow.style.display = 'block';
    document.getElementById('btn-prev').disabled = false;
    document.getElementById('btn-next').disabled = false;
    document.getElementById('btn-exp').disabled = false;

    buildPagesFlow();
    await waitForLayout();
    await fitAllPages();
    scheduleRefit();
    buildThumbnailPlaceholders();
    scheduleThumbRender(pageNum);
    showToast('✅ ' + name + ' · ' + numPages + ' pagini');
  } catch (err) {
    console.error(err);
    showToast('❌ ' + (err.message || 'Eroare la încărcare'));
  }
  setLoading(false);
}

window.openPdfBase64 = async function(b64, name) {
  try {
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    await loadPdfBuffer(bytes.buffer, name || 'document.pdf');
  } catch (e) {
    showToast('❌ Eroare la deschidere PDF');
  }
};

window.openHtmlBase64 = async function(b64, name) {
  try {
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const text = new TextDecoder('utf-8').decode(bytes);
    await openWebPage({ url: name || 'pagina.html', source: text, name: name || 'pagina.html', fetchOk: true, local: true });
  } catch (e) {
    showToast('❌ Eroare la deschidere HTML');
  }
};

const RECENT_KEY = 'pdf-editor-recent';
function addRecent(item) {
  try {
    let list = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]');
    list = list.filter(x => x.url !== item.url);
    list.unshift({ name: item.name, url: item.url, type: item.type || 'web', t: Date.now() });
    list = list.slice(0, 8);
    localStorage.setItem(RECENT_KEY, JSON.stringify(list));
    renderRecentList();
  } catch (_) {}
}

function renderRecentList() {
  const el = document.getElementById('recent-list');
  let list = [];
  try { list = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]'); } catch (_) {}
  if (!list.length) { el.innerHTML = ''; return; }
  el.innerHTML = '<div style="font-size:11px;color:var(--muted);margin-top:8px">Recente (web):</div>';
  list.forEach(item => {
    if (!item.url) return;
    const b = document.createElement('button');
    b.className = 'recent-item';
    const icon = item.type === 'pdf' ? '📄' : '🌐';
    b.textContent = icon + ' ' + (item.name || item.url);
    b.title = item.url;
    b.onclick = () => openFromUrl(item.url);
    el.appendChild(b);
  });
}

document.getElementById('url-cancel').addEventListener('click', () => {
  document.getElementById('url-dialog').classList.remove('on');
});
bindTap(document.getElementById('url-ok'), () => {
  const url = document.getElementById('url-inp').value;
  document.getElementById('url-dialog').classList.remove('on');
  openFromUrl(url);
});
document.getElementById('url-inp').addEventListener('keydown', e => {
  if (e.key === 'Enter') document.getElementById('url-ok').click();
});

bindTap(document.getElementById('btn-help'), () => {
  document.getElementById('help-dialog').classList.add('on');
});
bindTap(document.getElementById('help-ok'), () => {
  document.getElementById('help-dialog').classList.remove('on');
});

document.querySelectorAll('.web-tab').forEach(btn => {
  bindTap(btn, () => setWebTab(btn.dataset.tab));
});
bindTap(document.getElementById('web-close'), () => {
  destroyPdf();
  destroyWeb();
  setViewerMode('none');
  document.getElementById('btn-prev').disabled = true;
  document.getElementById('btn-next').disabled = true;
  document.getElementById('btn-exp').disabled = true;
  showToast('Închis');
});
bindTap(document.getElementById('web-refresh'), () => {
  renderWebVisual({ local: webIsLocal, url: currentWebUrl, source: localHtmlSource || storedHtmlSource });
});

renderRecentList();

// ── Render page ─────────────────────────────────────────────────────────────
function viewerPadding() {
  return 16;
}

function getViewerSize() {
  const rect = viewer.getBoundingClientRect();
  const vw = window.visualViewport?.width || rect.width || window.innerWidth;
  const vh = window.visualViewport?.height || rect.height || window.innerHeight;
  const pad = viewerPadding();
  return {
    w: Math.max(80, Math.min(rect.width, vw) - pad),
    h: Math.max(80, Math.min(rect.height, vh) - pad)
  };
}

async function waitForLayout() {
  await new Promise(r => requestAnimationFrame(r));
  await new Promise(r => requestAnimationFrame(r));
  if (getViewerSize().w < 80) {
    await new Promise(r => setTimeout(r, 80));
  }
}

let refitTimer = null;
function scheduleRefit() {
  clearTimeout(refitTimer);
  refitTimer = setTimeout(async () => {
    if (pdfDoc) await fitAllPages();
  }, 200);
}

function renderScale() {
  return scale * outputDpr;
}

function computeFitScale(vp1) {
  const { w } = getViewerSize();
  return Math.max(0.2, Math.min(5.0, w / vp1.width));
}

async function renderPageSlot(num) {
  if (!pdfDoc) return;
  const slot = pageSlots.get(num);
  if (!slot || slot.rendered || slot.rendering) return;
  slot.rendering = true;

  outputDpr = Math.min(window.devicePixelRatio || 1, 2.5);
  const key = pageCacheKey(num);
  const cached = pagePdfCache.get(key);

  try {
    const page = await pdfDoc.getPage(num);
    const vpRender = page.getViewport({ scale: renderScale() });
    const vpDisplay = page.getViewport({ scale });
    const cssW = Math.floor(vpDisplay.width);
    const cssH = Math.floor(vpDisplay.height);

    slot.pdfCvs.width = slot.annCvs.width = Math.floor(vpRender.width);
    slot.pdfCvs.height = slot.annCvs.height = Math.floor(vpRender.height);
    slot.pdfCvs.style.width = cssW + 'px';
    slot.pdfCvs.style.height = cssH + 'px';
    slot.annCvs.style.width = cssW + 'px';
    slot.annCvs.style.height = cssH + 'px';

    if (cached?.bitmap) {
      slot.pdfCtx.clearRect(0, 0, slot.pdfCvs.width, slot.pdfCvs.height);
      slot.pdfCtx.drawImage(cached.bitmap, 0, 0);
    } else if (cached?.fallback) {
      slot.pdfCtx.clearRect(0, 0, slot.pdfCvs.width, slot.pdfCvs.height);
      slot.pdfCtx.drawImage(cached.fallback, 0, 0);
    } else {
      await page.render({ canvasContext: slot.pdfCtx, viewport: vpRender }).promise;
      let bitmap;
      try { bitmap = await createImageBitmap(slot.pdfCvs); } catch (_) { bitmap = null; }
      pagePdfCache.set(key, { bitmap, fallback: null });
      if (!pageCacheOrder.includes(key)) pageCacheOrder.push(key);
      evictPageCache();
    }
    try { page.cleanup(); } catch (_) {}

    slot.placeholder.style.display = 'none';
    slot.pdfCvs.style.display = 'block';
    slot.annCvs.style.display = 'block';
    slot.rendered = true;
    redrawSlot(num);
  } catch (e) {
    console.warn('render page', num, e);
  }
  slot.rendering = false;
}

function redrawSlot(num) {
  const slot = pageSlots.get(num);
  if (!slot || !slot.rendered) return;
  slot.annCtx.clearRect(0, 0, slot.annCvs.width, slot.annCvs.height);
  (annData[num] || []).forEach(s => drawStroke(slot.annCtx, s, renderScale()));
}

function redrawAll(num) {
  redrawSlot(num);
}

function invalidateAllSlots() {
  pageSlots.forEach(slot => {
    slot.rendered = false;
    slot.rendering = false;
    slot.placeholder.style.display = '';
    slot.pdfCvs.style.display = 'none';
    slot.annCvs.style.display = 'none';
  });
}

async function fitAllPages() {
  if (!pdfDoc) return;
  clearPageCache();
  const page = await pdfDoc.getPage(1);
  const vp1 = page.getViewport({ scale: 1 });
  scale = computeFitScale(vp1);
  try { page.cleanup(); } catch (_) {}
  invalidateAllSlots();
  pagesFlow.style.transform = '';
  await renderVisiblePages();
  scrollToPage(pageNum, false);
}

function updateNav() {
  const info = document.getElementById('page-info');
  info.textContent = pdfDoc ? pageNum + ' / ' + numPages : '— / —';
  document.getElementById('btn-prev').disabled = !pdfDoc || pageNum <= 1;
  document.getElementById('btn-next').disabled = !pdfDoc || pageNum >= numPages;
  const jump = document.getElementById('page-jump');
  jump.max = numPages || 1;
  jump.value = pageNum;
}

async function goPage(d) {
  if (!pdfDoc) return;
  const n = pageNum + d;
  if (n < 1 || n > numPages) return;
  scrollToPage(n, true);
}

async function jumpToPage(n) {
  if (!pdfDoc) return;
  n = Math.max(1, Math.min(numPages, parseInt(n, 10) || 1));
  if (n === pageNum) return;
  scrollToPage(n, true);
}

async function doZoom(d) {
  if (!pdfDoc) return;
  clearPageCache();
  scale = Math.max(0.35, Math.min(5.0, scale + d));
  invalidateAllSlots();
  pagesFlow.style.transform = '';
  await renderVisiblePages();
}

async function fitPage() {
  await fitAllPages();
}

// Page jump UI
document.getElementById('page-info').addEventListener('click', () => {
  if (!pdfDoc) return;
  const info = document.getElementById('page-info');
  const jump = document.getElementById('page-jump');
  info.style.display = 'none';
  jump.style.display = 'inline-block';
  jump.value = pageNum;
  jump.focus();
  jump.select();
});
document.getElementById('page-jump').addEventListener('blur', () => {
  document.getElementById('page-info').style.display = '';
  document.getElementById('page-jump').style.display = 'none';
});
document.getElementById('page-jump').addEventListener('keydown', e => {
  if (e.key === 'Enter') {
    jumpToPage(e.target.value);
    e.target.blur();
  }
  if (e.key === 'Escape') e.target.blur();
});

document.getElementById('btn-prev').addEventListener('click', () => goPage(-1));
document.getElementById('btn-next').addEventListener('click', () => goPage(1));
document.getElementById('btn-zoom-in').addEventListener('click', () => doZoom(0.25));
document.getElementById('btn-zoom-out').addEventListener('click', () => doZoom(-0.25));
document.getElementById('btn-fit').addEventListener('click', fitPage);
document.getElementById('btn-theme').addEventListener('click', toggleTheme);

let resizeTimer;
window.addEventListener('resize', () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => { if (pdfDoc) scheduleRefit(); }, 200);
});
if (window.visualViewport) {
  window.visualViewport.addEventListener('resize', () => {
    if (pdfDoc) scheduleRefit();
  });
}

// ── Thumbnail sidebar ───────────────────────────────────────────────────────
document.getElementById('btn-thumbs').addEventListener('click', () => {
  sidebar.classList.toggle('open');
  document.getElementById('btn-thumbs').classList.toggle('active', sidebar.classList.contains('open'));
  if (sidebar.classList.contains('open') && pdfDoc) {
    for (let i = Math.max(1, pageNum - 2); i <= Math.min(numPages, pageNum + 4); i++) {
      scheduleThumbRender(i);
    }
  }
});

function buildThumbnailPlaceholders() {
  sidebar.innerHTML = '';
  for (let i = 1; i <= numPages; i++) {
    const div = document.createElement('div');
    div.className = 'thumb' + (i === pageNum ? ' active' : '');
    div.dataset.page = i;
    div.innerHTML = '<canvas></canvas><span class="thumb-num">' + i + '</span>';
    div.addEventListener('click', () => jumpToPage(i));
    sidebar.appendChild(div);
  }
}

function highlightThumb(n) {
  sidebar.querySelectorAll('.thumb').forEach(el => {
    el.classList.toggle('active', parseInt(el.dataset.page, 10) === n);
  });
  const active = sidebar.querySelector('.thumb.active');
  if (active) active.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

function evictThumbIfNeeded() {
  while (thumbOrder.length > THUMB_MAX) {
    const p = thumbOrder.shift();
    const entry = thumbCache.get(p);
    if (entry?.canvas) {
      entry.canvas.width = 0;
      entry.canvas.height = 0;
    }
    thumbCache.delete(p);
    const el = sidebar.querySelector('.thumb[data-page="' + p + '"] canvas');
    if (el) { el.width = 0; el.height = 0; }
  }
}

let thumbQueue = [];
let thumbBusy = false;

function scheduleThumbRender(p) {
  if (!pdfDoc || p < 1 || p > numPages) return;
  if (thumbCache.has(p)) return;
  if (!thumbQueue.includes(p)) thumbQueue.push(p);
  processThumbQueue();
}

async function processThumbQueue() {
  if (thumbBusy || !thumbQueue.length) return;
  thumbBusy = true;
  const p = thumbQueue.shift();
  try {
    if (!thumbCache.has(p) && pdfDoc) {
      const page = await pdfDoc.getPage(p);
      const THUMB_SCALE = 0.2;
      const vp = page.getViewport({ scale: THUMB_SCALE });
      const cvs = document.createElement('canvas');
      cvs.width = vp.width;
      cvs.height = vp.height;
      await page.render({ canvasContext: cvs.getContext('2d'), viewport: vp }).promise;
      try { page.cleanup(); } catch (_) {}

      thumbCache.set(p, { canvas: cvs });
      thumbOrder.push(p);
      evictThumbIfNeeded();

      const el = sidebar.querySelector('.thumb[data-page="' + p + '"] canvas');
      if (el) {
        el.width = cvs.width;
        el.height = cvs.height;
        el.getContext('2d').drawImage(cvs, 0, 0);
      }
    }
  } catch (e) {
    console.warn('Thumb', p, e);
  }
  thumbBusy = false;
  if (thumbQueue.length) processThumbQueue();
}

// ── Tools ───────────────────────────────────────────────────────────────────
function setTool(t) {
  curTool = t;
  document.querySelectorAll('.tbtn').forEach(b => b.classList.remove('active'));
  document.getElementById('t-' + t)?.classList.add('active');
  const drawing = t !== 'scroll';
  pageSlots.forEach(slot => {
    slot.annCvs.style.pointerEvents = drawing ? 'auto' : 'none';
    slot.annCvs.style.touchAction = drawing ? 'none' : 'auto';
  });
  viewer.style.touchAction = drawing ? 'pan-y' : 'pan-y pinch-zoom';
}

document.querySelectorAll('.tbtn').forEach(btn => {
  btn.addEventListener('click', () => setTool(btn.dataset.tool));
});

function slotFromEvent(e) {
  const ann = e.target.closest('.ann-layer');
  if (!ann) return null;
  const frame = ann.closest('.page-frame');
  if (!frame) return null;
  const n = parseInt(frame.dataset.page, 10);
  return { n, slot: pageSlots.get(n) };
}

function pointerPos(e, annCvs) {
  const rect = annCvs.getBoundingClientRect();
  const rx = annCvs.width / rect.width;
  const ry = annCvs.height / rect.height;
  return { x: (e.clientX - rect.left) * rx, y: (e.clientY - rect.top) * ry };
}

function onPointerDown(e) {
  if (curTool === 'scroll' || pinchActive) return;
  const hit = slotFromEvent(e);
  if (!hit?.slot?.rendered) return;
  if (activePointerId !== null) return;
  activeDrawSlot = hit.slot;
  pageNum = hit.n;
  updateNav();
  activePointerId = e.pointerId;
  try { hit.slot.annCvs.setPointerCapture(e.pointerId); } catch (_) {}

  const { x, y } = pointerPos(e, hit.slot.annCvs);
  isDrawing = true;

  if (curTool === 'text') {
    isDrawing = false;
    activePointerId = null;
    promptText(x, y);
    return;
  }
  if (curTool === 'note') {
    isDrawing = false;
    activePointerId = null;
    promptNote(x, y);
    return;
  }

  const docX = px2doc(x), docY = px2doc(y);
  const ctx = hit.slot.annCtx;

  if (curTool === 'pen') {
    liveStroke = {
      type: 'pen', color: getColor(), size: getSize(),
      pts: [{ x: docX, y: docY }], drawScale: scale
    };
    drawPenSegment(ctx, x, y, x, y, liveStroke);
  } else if (curTool === 'eraser') {
    const sz = getSize();
    liveStroke = {
      type: 'eraser', size: sz,
      rects: [{ x: docX - sz / 2 / scale, y: docY - sz / 2 / scale, w: sz / scale, h: sz / scale }],
      drawScale: scale
    };
    drawEraserRect(ctx, x, y, sz);
  } else {
    snapBase = ctx.getImageData(0, 0, hit.slot.annCvs.width, hit.slot.annCvs.height);
    liveStroke = {
      type: curTool, color: getColor(), size: getSize(),
      x: docX, y: docY, x2: docX, y2: docY, drawScale: scale
    };
  }
  e.preventDefault();
}

function onPointerMove(e) {
  if (!isDrawing || e.pointerId !== activePointerId || curTool === 'scroll' || !activeDrawSlot) return;
  const { x, y } = pointerPos(e, activeDrawSlot.annCvs);
  const ctx = activeDrawSlot.annCtx;

  if (curTool === 'pen') {
    const pts = liveStroke.pts;
    const last = pts[pts.length - 1];
    drawPenSegment(ctx, doc2px(last.x), doc2px(last.y), x, y, liveStroke);
    pts.push({ x: px2doc(x), y: px2doc(y) });
  } else if (curTool === 'eraser') {
    const sz = getSize();
    const docX = px2doc(x), docY = px2doc(y);
    liveStroke.rects.push({
      x: docX - sz / 2 / scale, y: docY - sz / 2 / scale,
      w: sz / scale, h: sz / scale
    });
    drawEraserRect(ctx, x, y, sz);
  } else {
    if (snapBase) ctx.putImageData(snapBase, 0, 0);
    liveStroke.x2 = px2doc(x);
    liveStroke.y2 = px2doc(y);
    drawStroke(ctx, liveStroke, renderScale());
  }
  e.preventDefault();
}

function onPointerUp(e) {
  if (e.pointerId !== activePointerId) return;
  if (activeDrawSlot) {
    try { activeDrawSlot.annCvs.releasePointerCapture(e.pointerId); } catch (_) {}
  }
  activePointerId = null;

  if (!isDrawing) return;
  isDrawing = false;
  if (!liveStroke) return;

  if (!annData[pageNum]) annData[pageNum] = [];
  annData[pageNum].push(liveStroke);
  liveStroke = null;
  snapBase = null;
  redrawSlot(pageNum);
  persistStroke();
  activeDrawSlot = null;
}

pagesFlow.addEventListener('pointerdown', onPointerDown);
pagesFlow.addEventListener('pointermove', onPointerMove);
pagesFlow.addEventListener('pointerup', onPointerUp);
pagesFlow.addEventListener('pointercancel', onPointerUp);

// ── Pinch-to-zoom (touch events — reliable on Android WebView) ──────────────
function touchDist(touches) {
  const dx = touches[0].clientX - touches[1].clientX;
  const dy = touches[0].clientY - touches[1].clientY;
  return Math.hypot(dx, dy);
}

function applyPinchPreview(newScale) {
  pinchPreviewScale = newScale;
  pagesFlow.style.transform = 'scale(' + (newScale / scale) + ')';
  pagesFlow.dataset.pinchScale = String(newScale);
}

async function commitPinch() {
  const pending = parseFloat(pagesFlow.dataset.pinchScale);
  pagesFlow.style.transform = '';
  pinchPreviewScale = null;
  delete pagesFlow.dataset.pinchScale;
  pinchActive = false;
  pinchStartDist = 0;
  pinchPointers.clear();
  if (pending && Math.abs(pending - scale) > 0.02) {
    clearPageCache();
    scale = pending;
    invalidateAllSlots();
    await renderVisiblePages();
  }
}

viewer.addEventListener('touchstart', e => {
  if (!pdfDoc || curTool !== 'scroll') return;
  if (e.touches.length === 2) {
    pinchActive = true;
    pinchStartDist = touchDist(e.touches);
    pinchStartScale = scale;
    isDrawing = false;
    liveStroke = null;
    activePointerId = null;
    e.preventDefault();
  }
}, { passive: false });

viewer.addEventListener('touchmove', e => {
  if (!pdfDoc) return;
  if (pinchActive && e.touches.length === 2 && pinchStartDist > 0) {
    const dist = touchDist(e.touches);
    const ratio = dist / pinchStartDist;
    const newScale = Math.max(0.25, Math.min(5.0, pinchStartScale * ratio));
    applyPinchPreview(newScale);
    e.preventDefault();
  }
}, { passive: false });

viewer.addEventListener('touchend', e => {
  if (pinchActive && e.touches.length < 2) commitPinch();
}, { passive: true });

viewer.addEventListener('touchcancel', () => {
  if (pinchActive) commitPinch();
}, { passive: false });

pagesFlow.addEventListener('pointerdown', e => {
  if (!pdfDoc || curTool !== 'scroll') return;
  pinchPointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (pinchPointers.size === 2) {
    pinchActive = true;
    pinchStartDist = pinchDistance();
    pinchStartScale = scale;
    e.preventDefault();
  }
}, { passive: false });

pagesFlow.addEventListener('pointermove', e => {
  if (!pinchActive || !pinchPointers.has(e.pointerId)) return;
  pinchPointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (pinchPointers.size === 2 && pinchStartDist > 0) {
    const ratio = pinchDistance() / pinchStartDist;
    applyPinchPreview(Math.max(0.25, Math.min(5.0, pinchStartScale * ratio)));
    e.preventDefault();
  }
}, { passive: false });

pagesFlow.addEventListener('pointerup', e => {
  pinchPointers.delete(e.pointerId);
  if (pinchActive && pinchPointers.size < 2) commitPinch();
});
pagesFlow.addEventListener('pointercancel', e => {
  pinchPointers.delete(e.pointerId);
  if (pinchActive && pinchPointers.size < 2) commitPinch();
});

function pinchDistance() {
  const pts = [...pinchPointers.values()];
  if (pts.length < 2) return 0;
  const dx = pts[1].x - pts[0].x;
  const dy = pts[1].y - pts[0].y;
  return Math.hypot(dx, dy);
}

function drawPenSegment(ctx, x1, y1, x2, y2, stroke) {
  ctx.save();
  ctx.strokeStyle = stroke?.color || getColor();
  ctx.lineWidth = stroke?.size || getSize();
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.beginPath();
  ctx.moveTo(x1, y1);
  ctx.lineTo(x2, y2);
  ctx.stroke();
  ctx.restore();
}

function drawEraserRect(ctx, cx, cy, sz) {
  ctx.save();
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(cx - sz / 2, cy - sz / 2, sz, sz);
  ctx.restore();
}

function drawStroke(ctx, s, sc) {
  sc = sc || scale;
  ctx.save();
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';

  const baseScale = (s.drawScale || scale) * outputDpr;
  const scaledLW = s.size * sc / baseScale;

  if (s.type === 'eraser') {
    ctx.fillStyle = '#ffffff';
    (s.rects || []).forEach(r => {
      ctx.fillRect(r.x * sc, r.y * sc, r.w * sc, r.h * sc);
    });
  } else if (s.type === 'pen') {
    ctx.strokeStyle = s.color;
    ctx.lineWidth = Math.max(1, scaledLW);
    ctx.beginPath();
    (s.pts || []).forEach((p, i) => {
      const px = p.x * sc, py = p.y * sc;
      i === 0 ? ctx.moveTo(px, py) : ctx.lineTo(px, py);
    });
    ctx.stroke();
  } else if (s.type === 'marker') {
    const [r, g, b] = hexRGB(s.color);
    ctx.fillStyle = 'rgba(' + r + ',' + g + ',' + b + ',0.33)';
    const x = Math.min(s.x, s.x2) * sc;
    const y = Math.min(s.y, s.y2) * sc;
    const w = Math.abs(s.x2 - s.x) * sc;
    const h = Math.abs(s.y2 - s.y) * sc;
    if (w > 1 && h > 1) ctx.fillRect(x, y, w, h);
  } else if (s.type === 'line') {
    ctx.strokeStyle = s.color;
    ctx.lineWidth = Math.max(1, scaledLW);
    ctx.beginPath();
    ctx.moveTo(s.x * sc, s.y * sc);
    ctx.lineTo(s.x2 * sc, s.y2 * sc);
    ctx.stroke();
  } else if (s.type === 'rect') {
    ctx.strokeStyle = s.color;
    ctx.lineWidth = Math.max(1, scaledLW);
    const x = Math.min(s.x, s.x2) * sc;
    const y = Math.min(s.y, s.y2) * sc;
    const w = Math.abs(s.x2 - s.x) * sc;
    const h = Math.abs(s.y2 - s.y) * sc;
    if (w > 0 && h > 0) ctx.strokeRect(x, y, w, h);
  } else if (s.type === 'arrow') {
    ctx.strokeStyle = s.color;
    ctx.lineWidth = Math.max(1, scaledLW);
    const fx = s.x * sc, fy = s.y * sc;
    const tx = s.x2 * sc, ty = s.y2 * sc;
    const ang = Math.atan2(ty - fy, tx - fx);
    const hl = Math.max(10, scaledLW * 5);
    ctx.beginPath();
    ctx.moveTo(fx, fy);
    ctx.lineTo(tx, ty);
    ctx.moveTo(tx - hl * Math.cos(ang - Math.PI / 7), ty - hl * Math.sin(ang - Math.PI / 7));
    ctx.lineTo(tx, ty);
    ctx.lineTo(tx - hl * Math.cos(ang + Math.PI / 7), ty - hl * Math.sin(ang + Math.PI / 7));
    ctx.stroke();
  } else if (s.type === 'text') {
    const fs = Math.max(10, scaledLW * 0.5 + 12);
    ctx.font = fs + 'px system-ui,sans-serif';
    ctx.fillStyle = s.color;
    ctx.fillText(s.text, s.x * sc, s.y * sc);
  } else if (s.type === 'note') {
    const pad = 8 * sc / baseScale;
    const fs = Math.max(11, scaledLW * 0.4 + 11);
    const lines = (s.text || '').split('\n');
    const lineH = fs * 1.3;
    const w = Math.max(120 * sc / baseScale, (s.w || 140) * sc);
    const h = Math.max(60 * sc / baseScale, pad * 2 + lines.length * lineH);
    const x = s.x * sc, y = s.y * sc;

    ctx.fillStyle = '#fff3a3';
    ctx.strokeStyle = '#e6c200';
    ctx.lineWidth = 1;
    ctx.fillRect(x, y, w, h);
    ctx.strokeRect(x, y, w, h);

    ctx.fillStyle = '#1a1a1a';
    ctx.font = fs + 'px system-ui,sans-serif';
    lines.forEach((line, i) => {
      ctx.fillText(line, x + pad, y + pad + fs + i * lineH);
    });
  }
  ctx.restore();
}

// ── Annotation actions ──────────────────────────────────────────────────────
function undo() {
  if (!annData[pageNum]?.length) { showToast('Nimic de anulat'); return; }
  annData[pageNum].pop();
  redrawAll(pageNum);
  persistStroke();
  showToast('↩ Anulat');
}

function clearPage() {
  if (!annData[pageNum]?.length) return;
  if (!confirm('Șterge toate adnotările de pe pagina curentă?')) return;
  annData[pageNum] = [];
  redrawAll(pageNum);
  persistStroke();
  showToast('🗑 Pagina curată');
}

document.getElementById('btn-undo').addEventListener('click', undo);
document.getElementById('btn-clear').addEventListener('click', clearPage);

function promptText(cx, cy) {
  const txt = prompt('Text:');
  if (!txt?.trim()) return;
  if (!annData[pageNum]) annData[pageNum] = [];
  annData[pageNum].push({
    type: 'text', color: getColor(), size: getSize(),
    text: txt.trim(), x: px2doc(cx), y: px2doc(cy), drawScale: scale
  });
  redrawAll(pageNum);
  persistStroke();
}

function promptNote(cx, cy) {
  notePendingPos = { x: cx, y: cy };
  document.getElementById('note-text').value = '';
  document.getElementById('note-dialog').classList.add('on');
  document.getElementById('note-text').focus();
}

document.getElementById('note-cancel').addEventListener('click', () => {
  document.getElementById('note-dialog').classList.remove('on');
  notePendingPos = null;
});
document.getElementById('note-ok').addEventListener('click', () => {
  const txt = document.getElementById('note-text').value.trim();
  document.getElementById('note-dialog').classList.remove('on');
  if (!txt || !notePendingPos) { notePendingPos = null; return; }
  if (!annData[pageNum]) annData[pageNum] = [];
  annData[pageNum].push({
    type: 'note', text: txt,
    x: px2doc(notePendingPos.x), y: px2doc(notePendingPos.y),
    w: 140, size: getSize(), drawScale: scale
  });
  notePendingPos = null;
  redrawAll(pageNum);
  persistStroke();
});

// ── Export PDF (high quality) ───────────────────────────────────────────────
async function exportPDF() {
  if (!pdfDoc) return;
  setLoading(true, 'Se generează PDF-ul…');

  const tmpPdf = document.createElement('canvas');
  const tmpAnn = document.createElement('canvas');
  const ctxP = tmpPdf.getContext('2d', { alpha: false });
  const ctxA = tmpAnn.getContext('2d');
  let pdf = null;

  try {
    const { jsPDF } = window.jspdf;
    const EXP_SCALE = Math.min(3, Math.max(2, window.devicePixelRatio || 2));

    for (let p = 1; p <= numPages; p++) {
      setLoading(true, 'Export pagina ' + p + ' / ' + numPages + '…');
      const page = await pdfDoc.getPage(p);
      const vp = page.getViewport({ scale: EXP_SCALE });

      tmpPdf.width = tmpAnn.width = Math.floor(vp.width);
      tmpPdf.height = tmpAnn.height = Math.floor(vp.height);

      await page.render({ canvasContext: ctxP, viewport: vp }).promise;
      try { page.cleanup(); } catch (_) {}

      ctxA.clearRect(0, 0, tmpAnn.width, tmpAnn.height);
      (annData[p] || []).forEach(s => drawStroke(ctxA, s, EXP_SCALE));
      ctxP.drawImage(tmpAnn, 0, 0);

      const imgData = tmpPdf.toDataURL('image/png');
      const orient = vp.width > vp.height ? 'l' : 'p';
      const w = tmpPdf.width;
      const h = tmpPdf.height;

      if (p === 1) {
        pdf = new jsPDF({ orientation: orient, unit: 'px', format: [w, h], compress: true });
      } else {
        pdf.addPage([w, h], orient);
      }
      pdf.addImage(imgData, 'PNG', 0, 0, w, h, undefined, 'FAST');
    }

    const outName = currentFileName.replace(/\.pdf$/i, '') + '-adnotat.pdf';
    if (hasNativeSavePicker() && window.AndroidSave.pickSaveBase64) {
      const b64 = arrayBufferToBase64(pdf.output('arraybuffer'));
      const ok = await nativeSavePicker(b64, outName, 'application/pdf', true);
      if (ok) showToast('✅ PDF salvat');
    } else {
      pdf.save(outName);
      showToast('✅ PDF exportat!');
    }
  } catch (err) {
    console.error(err);
    showToast('❌ ' + (err.message || 'Eroare export'));
  } finally {
    tmpPdf.width = tmpAnn.width = 0;
    tmpPdf.height = tmpAnn.height = 0;
    setLoading(false);
  }
}
document.getElementById('btn-exp').addEventListener('click', exportPDF);

// ── Keyboard shortcuts ──────────────────────────────────────────────────────
document.addEventListener('keydown', e => {
  if (!pdfDoc) return;
  if (e.target.matches('input, textarea')) return;
  const k = e.key;
  if (k === 'ArrowLeft' || k === 'PageUp') { goPage(-1); return; }
  if (k === 'ArrowRight' || k === 'PageDown') { goPage(1); return; }
  if (k === '+' || k === '=') doZoom(0.25);
  if (k === '-') doZoom(-0.25);
  if (k === 'f' || k === 'F') fitPage();
  if (e.ctrlKey && k === 'z') { e.preventDefault(); undo(); }
  const tools = { '1': 'scroll', '2': 'pen', '3': 'marker', '4': 'text', '5': 'line', '6': 'rect', '7': 'arrow', '8': 'eraser', '9': 'note' };
  if (tools[k]) setTool(tools[k]);
});

// ── Editor fișiere noi (TXT / HTML) ─────────────────────────────────────────
const HTML_TEMPLATE = [
  '<!DOCTYPE html>',
  '<html lang="ro">',
  '<head>',
  '<meta charset="utf-8">',
  '<meta name="viewport" content="width=device-width,initial-scale=1">',
  '<title>Document nou</title>',
  '</head>',
  '<body>',
  '',
  '<p>Scrie aici...</p>',
  '',
  '</body>',
  '</html>'
].join('\n');

let editorFormat = 'txt';

function closeEditor() {
  const overlay = document.getElementById('editor-overlay');
  if (overlay) overlay.classList.remove('on');
  document.documentElement.classList.remove('editor-active');
}

function openNewDialog() {
  document.getElementById('new-dialog').classList.add('on');
}

function openEditor({ content = '', name = '', format = 'txt' } = {}) {
  destroyPdf();
  destroyWeb();
  setViewerMode('none');
  editorFormat = format === 'html' ? 'html' : 'txt';
  const overlay = document.getElementById('editor-overlay');
  const ta = document.getElementById('editor-content');
  const fn = document.getElementById('editor-filename');
  const sel = document.getElementById('editor-format');
  const defaultName = editorFormat === 'html' ? 'document.html' : 'notite.txt';
  fn.value = name || defaultName;
  sel.value = editorFormat;
  ta.value = content || (editorFormat === 'html' ? HTML_TEMPLATE : '');
  overlay.classList.add('on');
  document.documentElement.classList.add('editor-active');
  setTimeout(() => ta.focus(), 150);
  showToast('📝 Editor — scrie și apasă Salvează');
}

function editorFilename() {
  let name = (document.getElementById('editor-filename').value || '').trim();
  const fmt = document.getElementById('editor-format').value;
  if (!name) name = fmt === 'html' ? 'document.html' : 'notite.txt';
  if (fmt === 'html' && !name.toLowerCase().endsWith('.html') && !name.toLowerCase().endsWith('.htm')) {
    name += '.html';
  }
  if (fmt === 'txt' && !name.toLowerCase().endsWith('.txt')) {
    name += '.txt';
  }
  return name;
}

function editorMime() {
  return document.getElementById('editor-format').value === 'html' ? 'text/html' : 'text/plain';
}

let nativeSaveCallback = null;

window.onNativeSaveResult = function(ok, message) {
  if (message === 'cancel') {
    showToast('Salvare anulată');
  } else if (ok) {
    showToast('✅ Salvat: ' + message);
  } else {
    showToast('❌ ' + (message || 'Eroare la salvare'));
  }
  if (nativeSaveCallback) {
    nativeSaveCallback(ok);
    nativeSaveCallback = null;
  }
};

function hasNativeSavePicker() {
  return !!(window.AndroidSave && typeof window.AndroidSave.pickSave === 'function');
}

function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

function nativeSavePicker(content, filename, mimeType, isBinary) {
  return new Promise((resolve) => {
    nativeSaveCallback = resolve;
    try {
      if (isBinary && window.AndroidSave.pickSaveBase64) {
        window.AndroidSave.pickSaveBase64(content, filename, mimeType || 'application/octet-stream');
      } else {
        window.AndroidSave.pickSave(content, filename, mimeType || 'text/plain');
      }
    } catch (e) {
      nativeSaveCallback = null;
      console.error(e);
      resolve(false);
    }
  });
}

async function saveFileToDevice(content, filename, mimeType, isBinary) {
  if (hasNativeSavePicker()) {
    return nativeSavePicker(content, filename, mimeType, isBinary);
  }

  const blob = new Blob([content], { type: mimeType + ';charset=utf-8' });
  const file = new File([blob], filename, { type: mimeType });

  if (navigator.canShare && navigator.canShare({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title: filename });
      showToast('✅ Alege unde salvezi (Fișiere, Drive…)');
      return true;
    } catch (e) {
      if (e.name === 'AbortError') return false;
    }
  }

  if (navigator.share) {
    try {
      await navigator.share({ title: filename, text: content });
      showToast('✅ Partajat — salvează din app-ul ales');
      return true;
    } catch (e) {
      if (e.name === 'AbortError') return false;
    }
  }

  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
  showToast('✅ ' + filename);
  return true;
}

async function saveEditorFile() {
  const content = document.getElementById('editor-content').value;
  const filename = editorFilename();
  const mime = editorMime();
  await saveFileToDevice(content, filename, mime);
}

function previewEditorContent() {
  const content = document.getElementById('editor-content').value;
  const name = editorFilename();
  const fmt = document.getElementById('editor-format').value;
  closeEditor();
  if (fmt === 'html') {
    openWebPage({ url: name, source: content, name, fetchOk: true, local: true });
  } else {
    openWebPage({
      url: name,
      source: '<pre style="font-family:monospace;white-space:pre-wrap;padding:16px">' + content.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') + '</pre>',
      name,
      fetchOk: true,
      local: true
    });
  }
}

bindTap(document.getElementById('btn-new'), openNewDialog);
bindTap(document.getElementById('dz-new-btn'), openNewDialog);
bindTap(document.getElementById('new-cancel'), () => {
  document.getElementById('new-dialog').classList.remove('on');
});
bindTap(document.getElementById('new-txt'), () => {
  document.getElementById('new-dialog').classList.remove('on');
  openEditor({ format: 'txt' });
});
bindTap(document.getElementById('new-html'), () => {
  document.getElementById('new-dialog').classList.remove('on');
  openEditor({ format: 'html', content: HTML_TEMPLATE });
});
bindTap(document.getElementById('editor-close'), () => {
  closeEditor();
  setViewerMode('none');
});
bindTap(document.getElementById('editor-save'), saveEditorFile);
bindTap(document.getElementById('editor-preview'), previewEditorContent);
document.getElementById('editor-format').addEventListener('change', e => {
  const fmt = e.target.value;
  const fn = document.getElementById('editor-filename');
  if (fmt === 'html' && !fn.value.toLowerCase().includes('.html')) {
    fn.value = fn.value.replace(/\.txt$/i, '') || 'document';
    if (!fn.value.endsWith('.html')) fn.value += '.html';
  }
  if (fmt === 'txt' && fn.value.toLowerCase().endsWith('.html')) {
    fn.value = fn.value.replace(/\.html?$/i, '') + '.txt';
  }
});

// ── Init ────────────────────────────────────────────────────────────────────
destroyWeb();
closeEditor();
setViewerMode('none');
setTool('scroll');
updateNav();
