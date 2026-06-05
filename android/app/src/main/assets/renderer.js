// ===== 小风剧场 Android 版 v1.2 =====

const CATEGORY_IDS = { home: [13, 16, 15], domestic: 13, us: 16, international: [15, 22, 23] };
const CATEGORY_NAMES = { 13: '国产剧', 14: '香港剧', 15: '韩剧', 16: '美剧', 22: '日剧', 23: '海外剧' };
const bridge = window.androidBridge || {};

// ===== API 请求 =====
let lastApiCall = 0;
const apiCache = {};
const API_CACHE_TTL = 3 * 60 * 1000; // 3 分钟

async function nativeFetch(url) {
  try {
    // 检查缓存
    const now = Date.now();
    if (apiCache[url] && now - apiCache[url].t < API_CACHE_TTL) {
      return apiCache[url].data;
    }
    if (bridge.fetchApi) {
      const text = bridge.fetchApi(url);
      if (!text) return null;
      if (text.includes('WAF') || text.startsWith('<')) {
        await new Promise(r => setTimeout(r, 2000));
        const retry = bridge.fetchApi(url);
        if (!retry || retry.includes('WAF') || retry.startsWith('<')) return null;
        const data = JSON.parse(retry);
        apiCache[url] = { data, t: now };
        return data;
      }
      const data = JSON.parse(text);
      apiCache[url] = { data, t: now };
      return data;
    }
    const resp = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0' } });
    const data = await resp.json();
    apiCache[url] = { data, t: now };
    return data;
  } catch (e) { console.error('API:', e); return null; }
}

async function apiGet(params) {
  const now = Date.now();
  const wait = Math.max(0, 200 - (now - lastApiCall));
  if (wait > 0) await new Promise(r => setTimeout(r, wait));
  lastApiCall = Date.now();
  if (!params.ac) params.ac = 'videolist';
  const qs = new URLSearchParams();
  Object.keys(params).forEach(k => qs.set(k, params[k]));
  return await nativeFetch('https://cj.ffzyapi.com/api.php/provide/vod/from/ffm3u8?' + qs.toString());
}

function parsePlayUrl(u) {
  if (!u) return [];
  return u.split('#').map(e => { const [t, url] = e.split('$'); return { title: t || '', url: url || '' }; });
}

// ===== 卡片 =====
function createCard(item) {
  const card = document.createElement('div');
  card.className = 'video-card';
  card.dataset.vodId = item.vod_id;
  const score = item.vod_score > 0 ? item.vod_score : (item.vod_douban_score || '');
  const emoji = getEmoji(item.type_id);
  const hasPic = !!item.vod_pic;
  card.innerHTML = `
    ${score ? `<div class="card-badge">${score}</div>` : ''}
    <div class="card-thumb${hasPic ? '' : ' thumb-fallback'}">
      ${hasPic ? `<img data-src="${item.vod_pic}" loading="eager" referrerpolicy="no-referrer" onerror="loadImageFallback(this)">` : ''}
      <span class="thumb-emoji">${emoji}</span>
    </div>
    <div class="card-info">
      <div class="card-title">${item.vod_name || '未知'}</div>
      <div class="card-meta">${[item.vod_year, CATEGORY_NAMES[item.type_id]].filter(Boolean).join(' · ')}</div>
      ${item.vod_remarks ? `<div class="card-remarks">${item.vod_remarks}</div>` : ''}
    </div>`;
  card.addEventListener('click', () => openEpisodeSelect(item.vod_id, item.vod_name, item.vod_pic));
  // 触发图片加载
  requestAnimationFrame(() => loadCardImage(card));
  return card;
}

function loadCardImage(card) {
  const img = card.querySelector('img[data-src]');
  if (!img) return;
  const src = img.dataset.src;
  img.removeAttribute('data-src');
  img.src = src;
}

function loadImageFallback(img) {
  if (img.dataset.fallbackTried) return;
  img.dataset.fallbackTried = '1';
  img.style.display = 'none';
  img.parentElement.classList.add('thumb-fallback');
}
function getEmoji(id) { return {13:'🇨🇳',14:'🇭🇰',15:'🇰🇷',16:'🇺🇸',22:'🇯🇵',23:'🌍'}[id]||'🎬'; }

function renderGrid(gid, items) {
  const g = document.getElementById(gid);
  if (!g) return;
  g.innerHTML = '';
  if (!items || !items.length) { g.innerHTML = '<p style="color:#666;grid-column:1/-1;text-align:center;padding:40px">暂无数据</p>'; return; }
  items.forEach(i => g.appendChild(createCard(i)));
}

// ===== 首页 =====
async function loadHome() {
  const [a, b] = await Promise.all([apiGet({ t: 13, pg: 1, pagesize: 12 }), apiGet({ t: 16, pg: 1, pagesize: 12 })]);
  if (a?.list) {
    renderGrid('trending-grid', a.list.slice(0, 6));
    const h = a.list[0];
    if (h) {
      document.querySelector('.hero-title').textContent = h.vod_name;
      document.querySelector('.hero-desc').textContent = (h.vod_content || h.vod_remarks || '一起看剧吧 💨').slice(0, 80);
      const pb = document.getElementById('hero-play-btn');
      pb.style.display = 'inline-block';
      pb.onclick = () => openEpisodeSelect(h.vod_id, h.vod_name, h.vod_pic);
    }
  }
  if (b?.list) renderGrid('top-rated-grid', b.list);
}

// ===== 分类 =====
async function loadCategory(pid, filterClass = '', filterType = '') {
  const gid = pid + '-grid';
  const t = CATEGORY_IDS[pid];
  const pagesize = 40;
  if (Array.isArray(t)) {
    const all = [];
    if (filterClass === '其他') {
      const rs = await Promise.all(t.map(tt => apiGet({ t: tt, pg: 1, pagesize })));
      rs.forEach(r => { if (r?.list) all.push(...r.list); });
    } else if (filterType) {
      const tArr = filterType.split(',').map(Number);
      const rs = await Promise.all(tArr.map(tt => apiGet({ t: tt, pg: 1, pagesize })));
      rs.forEach(r => { if (r?.list) all.push(...r.list); });
    } else if (filterClass) {
      const rs = await Promise.all(t.map(tt => apiGet({ t: tt, pg: 1, pagesize, class: filterClass })));
      rs.forEach(r => { if (r?.list) all.push(...r.list); });
    } else {
      const rs = await Promise.all(t.map(tt => apiGet({ t: tt, pg: 1, pagesize })));
      rs.forEach(r => { if (r?.list) all.push(...r.list); });
    }
    renderGrid(gid, all);
  } else {
    const params = { t, pg: 1, pagesize };
    if (filterClass) params['class'] = filterClass;
    const d = await apiGet(params);
    if (d?.list) renderGrid(gid, d.list);
  }
}

// ===== 选集页面（原始逻辑，动态创建在 body 外） =====
async function openEpisodeSelect(vodId, vodName, vodPic) {
  const d = await apiGet({ ids: vodId });
  if (!d?.list?.[0]) return alert('获取失败');
  const item = d.list[0];
  const eps = parsePlayUrl(item.vod_play_url);
  if (!eps.length) return alert('暂无播放源');

  const prevHistory = getHistory().find(h => h.vodId === vodId);
  const prevEp = (prevHistory && prevHistory.epIdx !== undefined) ? prevHistory.epIdx : 0;
  const startEp = prevEp < eps.length ? prevEp : 0;

  window._currentVod = { vodId, vodName, vodPic, item, eps, startEp };

  document.getElementById('app-layout').style.display = 'none';

  let epPage = document.getElementById('ep-select-page');
  if (!epPage) {
    epPage = document.createElement('div');
    epPage.id = 'ep-select-page';
    epPage.className = 'ep-select-page';
    document.body.appendChild(epPage);
  }
  epPage.innerHTML = `
    <div class="ep-select-header">
      <button id="ep-select-back">← 返回</button>
      <span class="ep-select-title" id="ep-select-title"></span>
      <span></span>
    </div>
    <div class="ep-select-body" id="ep-select-grid"></div>`;
  epPage.style.display = 'flex';

  document.getElementById('ep-select-title').textContent = vodName;

  document.getElementById('ep-select-back').onclick = () => {
    epPage.style.display = 'none';
    document.getElementById('app-layout').style.display = 'flex';
  };

  const grid = document.getElementById('ep-select-grid');
  grid.innerHTML = eps.map((ep, i) =>
    `<button class="ep-select-btn${i === startEp ? ' current' : ''}" data-idx="${i}">${ep.title}<br><small>${i === startEp ? '上次看到这里' : ''}</small></button>`
  ).join('');

  grid.querySelectorAll('.ep-select-btn').forEach(btn => {
    btn.onclick = () => {
      const idx = parseInt(btn.dataset.idx);
      const ep = eps[idx];
      addHistory(vodId, vodName, vodPic || '', item.vod_remarks, idx, 0);
      if (bridge.playVideo) {
        const titles = eps.map(e => e.title).join('|||');
        const urls = eps.map(e => e.url).join('|||');
        bridge.playVideo(ep.url, vodName, idx, titles, urls);
      }
    };
  });
}

// ===== 历史记录 =====
const HISTORY_KEY = 'xiaofeng_history';

function getHistory() {
  try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]'); } catch { return []; }
}

function addHistory(vodId, vodName, vodPic, vodRemarks, epIdx, epTime) {
  const list = getHistory().filter(h => h.vodId !== vodId);
  list.unshift({ vodId, vodName, vodPic, vodRemarks, epIdx, epTime: Math.floor(epTime || 0), time: Date.now() });
  if (list.length > 100) list.length = 100;
  localStorage.setItem(HISTORY_KEY, JSON.stringify(list));
}

function loadHistory() {
  const list = getHistory();
  const gr = document.getElementById('search-results');
  if (!gr) return;
  const bar = document.querySelector('#page-search .search-bar');
  if (bar) bar.style.display = 'none';
  let title = document.getElementById('history-title');
  if (!title) {
    title = document.createElement('div');
    title.id = 'history-title';
    title.className = 'section-header';
    title.innerHTML = '<h2>📋 观看历史</h2><button class="btn btn-sm" id="clear-history-btn">清空</button>';
    gr.parentNode.insertBefore(title, gr);
  }
  document.getElementById('clear-history-btn').onclick = () => {
    if (confirm('确定清空观看历史？')) { localStorage.removeItem(HISTORY_KEY); loadHistory(); }
  };
  if (!list.length) { gr.innerHTML = '<div class="empty-state"><div class="empty-icon">📋</div>暂无观看记录</div>'; return; }
  gr.innerHTML = '';
  list.forEach(i => {
    const card = document.createElement('div');
    card.className = 'video-card';
    card.innerHTML = `
      ${i.vodPic ? `<div class="card-thumb"><img data-src="${i.vodPic}" loading="eager" referrerpolicy="no-referrer" onerror="loadImageFallback(this)"><span class="thumb-emoji">🎬</span></div>`
                 : `<div class="card-thumb thumb-fallback"><span class="thumb-emoji">🎬</span></div>`}
      <div class="card-info">
        <div class="card-title">${i.vodName || '未知'}</div>
        <div class="card-meta">${i.vodRemarks || ''}</div>
        ${i.epIdx !== undefined ? `<div class="card-remarks">▶ 第${i.epIdx+1}集</div>` : ''}
      </div>`;
    card.addEventListener('click', () => openEpisodeSelect(i.vodId, i.vodName, i.vodPic));
    gr.appendChild(card);
    requestAnimationFrame(() => loadCardImage(card));
  });
}

// ===== 搜索 =====
async function doSearch(query) {
  if (!query) query = document.getElementById('home-search-input').value.trim();
  if (!query) return;
  const d = await apiGet({ ac: 'list', wd: query, pg: 1, pagesize: 40 });
  const ht = document.getElementById('history-title');
  if (ht) ht.style.display = 'none';
  const sb = document.querySelector('#page-search .search-bar');
  if (sb) sb.style.display = '';
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.getElementById('page-search').classList.add('active');
  document.querySelectorAll('.mobile-nav-item').forEach(n => n.classList.remove('active'));
  const gr = document.getElementById('search-results');
  if (!d?.list || !d.list.length) {
    gr.innerHTML = '<div class="empty-state"><div class="empty-icon">🔍</div>未找到结果</div>';
  } else {
    gr.innerHTML = '';
    d.list.forEach(i => gr.appendChild(createCard(i)));
  }
}

function doSearchFromHome() {
  const q = document.getElementById('home-search-input').value.trim();
  if (!q) return;
  doSearch(q);
}

// ===== 导航 =====
document.querySelectorAll('.mobile-nav-item').forEach(item => {
  item.addEventListener('click', async () => {
    document.querySelectorAll('.mobile-nav-item').forEach(n => n.classList.remove('active'));
    item.classList.add('active');
    const pid = item.dataset.page;
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    document.getElementById('page-' + pid).classList.add('active');
    if (pid === 'home') await loadHome();
    else if (['domestic', 'us', 'international'].includes(pid)) await loadCategory(pid);
    else if (pid === 'search') loadHistory();
  });
});

// ===== 筛选 =====
document.querySelectorAll('.filter-bar').forEach(bar => {
  bar.addEventListener('click', e => {
    const t = e.target.closest('.filter-btn');
    if (!t) return;
    bar.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
    t.classList.add('active');
    const page = bar.closest('.page');
    if (page) {
      const pid = page.id.replace('page-', '');
      const ft = t.dataset.t || '';
      if (ft) { loadCategory(pid, '', ft); }
      else { loadCategory(pid, t.dataset['class'] || ''); }
    }
  });
});

// ===== 搜索触发 =====
document.getElementById('home-search-input')?.addEventListener('keydown', e => {
  if (e.key === 'Enter') doSearchFromHome();
});
document.getElementById('search-input')?.addEventListener('keydown', e => {
  if (e.key === 'Enter') { const q = e.target.value.trim(); if (q) doSearch(q); }
});

function toggleMobileSearch() {
  const bar = document.getElementById('mobile-search-bar');
  if (bar) bar.style.display = bar.style.display === 'flex' ? 'none' : 'flex';
}

// ===== 启动 =====
loadHome();
console.log('💨 小风剧场 v1.2 已启动！');
