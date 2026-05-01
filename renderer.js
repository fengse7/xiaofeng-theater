// ===== 小风剧场 - 主渲染器 =====

const CATEGORY_IDS = {
  home: [13, 16, 15],
  domestic: 13,
  us: 16,
  international: [15, 22, 23],
};
const CATEGORY_NAMES = { 13: '国产剧', 14: '香港剧', 15: '韩剧', 16: '美剧', 22: '日剧', 23: '海外剧' };

// ===== API =====
let lastApiCall = 0;

async function apiGet(params, retries = 2) {
  // 请求间隔：至少 800ms
  const now = Date.now();
  const wait = Math.max(0, 800 - (now - lastApiCall));
  if (wait > 0) await new Promise(r => setTimeout(r, wait));
  lastApiCall = Date.now();

  // 允许外部传入 ac 参数（如搜索用 ac=list），默认 videolist
  if (!params.ac) params.ac = 'videolist';
  const qs = new URLSearchParams();
  Object.keys(params).forEach(k => {
    qs.set(k, params[k]);
  });
  const url = 'https://cj.ffzyapi.com/api.php/provide/vod/from/ffm3u8?' + qs.toString();
  console.log('API请求:', url);
  try {
    const resp = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0' } });
    console.log('API响应状态:', resp.status);
    const data = await resp.json();
    console.log('API结果:', data?.total, '条');
    return data;
  } catch (e) { 
    console.error('API错误:', e); 
    if (retries > 0) {
      await new Promise(r => setTimeout(r, 2000));
      return apiGet(params, retries - 1);
    }
    return null; 
  }
}

// ===== 卡片 =====
function createCard(item) {
  const card = document.createElement('div');
  card.className = 'video-card';
  card.dataset.vodId = item.vod_id;
  const score = item.vod_score > 0 ? item.vod_score : (item.vod_douban_score || '');
  const emoji = getEmoji(item.type_id);
  const hasPic = !!item.vod_pic;

  let thumbHtml;
  if (hasPic) {
    thumbHtml = `<div class="card-thumb"><img src="${item.vod_pic}" alt="" loading="lazy" onerror="this.style.display='none';this.parentElement.classList.add('thumb-fallback')"><span class="thumb-emoji">${emoji}</span></div>`;
  } else {
    thumbHtml = `<div class="card-thumb thumb-fallback"><span class="thumb-emoji">${emoji}</span></div>`;
  }

  card.innerHTML = `
    ${score ? `<div class="card-badge">${score}</div>` : ''}
    ${thumbHtml}
    <div class="card-info">
      <div class="card-title" title="${item.vod_name || ''}">${item.vod_name || '未知'}</div>
      <div class="card-meta">${[item.vod_year, CATEGORY_NAMES[item.type_id]].filter(Boolean).join(' · ')}</div>
      ${item.vod_remarks ? `<div class="card-remarks">${item.vod_remarks}</div>` : ''}
    </div>`;
  card.addEventListener('click', () => openPlayer(item.vod_id, item.vod_name));
  return card;
}

function getEmoji(id) { return {13:'🇨🇳',14:'🇭🇰',15:'🇰🇷',16:'🇺🇸',22:'🇯🇵',23:'🌍',4:'🎨',6:'💥',7:'😂',9:'🚀'}[id]||'🎬'; }

function renderGrid(gid, items) {
  const g = document.getElementById(gid);
  if (!g) return;
  g.innerHTML = '';
  if (!items || !items.length) { g.innerHTML = '<p style="color:#666;grid-column:1/-1;text-align:center;padding:40px">暂无数据</p>'; return; }
  items.forEach(i => g.appendChild(createCard(i)));
}

// ===== 加载 =====
async function loadHome() {
  const [a, b] = await Promise.all([
    apiGet({ t: 13, pg: 1, pagesize: 12 }),
    apiGet({ t: 16, pg: 1, pagesize: 12 })
  ]);
  if (a?.list) {
    renderGrid('trending-grid', a.list.slice(0, 6));
    const h = a.list[0];
    if (h) {
      document.querySelector('.hero-title').textContent = h.vod_name;
      document.querySelector('.hero-desc').textContent = (h.vod_content || h.vod_remarks || '一起看剧吧 💨').slice(0, 80);
      const he = document.getElementById('hero');
      if (h.vod_pic) he.style.background = `linear-gradient(135deg, #1a1a2e, #16213e), url(${h.vod_pic}) center/cover`;
      const pb = document.getElementById('hero-play-btn');
      pb.style.display = 'inline-block';
      pb.onclick = () => openPlayer(h.vod_id, h.vod_name);
    }
  }
  if (b?.list) renderGrid('top-rated-grid', b.list);
}

async function loadCategory(pid, filterClass = '', filterType = '') {
  const gid = pid + '-grid';
  const t = CATEGORY_IDS[pid];
  const pagesize = 40;

  if (Array.isArray(t)) {
    // 多分类组合
    let allItems = [];
    if (filterClass === '其他') {
      const rs = await Promise.all(t.map(tt => apiGet({ t: tt, pg: 1, pagesize })));
      rs.forEach(r => { if (r?.list) allItems.push(...r.list); });
    } else if (filterType) {
      const tArr = filterType.split(',').map(Number);
      const rs = await Promise.all(tArr.map(tt => apiGet({ t: tt, pg: 1, pagesize })));
      rs.forEach(r => { if (r?.list) allItems.push(...r.list); });
    } else if (filterClass) {
      const rs = await Promise.all(t.map(tt => apiGet({ t: tt, pg: 1, pagesize, class: filterClass })));
      rs.forEach(r => { if (r?.list) allItems.push(...r.list); });
    } else {
      const rs = await Promise.all(t.map(tt => apiGet({ t: tt, pg: 1, pagesize })));
      rs.forEach(r => { if (r?.list) allItems.push(...r.list); });
    }
    renderGrid(gid, allItems);
  } else {
    const params = { t, pg: 1, pagesize };
    if (filterClass) params['class'] = filterClass;
    const d = await apiGet(params);
    if (d?.list) renderGrid(gid, d.list);
  }
}

// ===== 播放器 =====
let hlsInstance = null;
let isFullscreenMode = false;
let fsHideTimer = null;
let fsMouseMoveHandler = null;

// 全屏时鼠标闲置自动隐藏全屏按钮
function setupFsAutoHide(fsBtn) {
  if (fsMouseMoveHandler) {
    document.removeEventListener('mousemove', fsMouseMoveHandler);
  }
  const showBtn = () => {
    if (fsBtn) fsBtn.style.opacity = '1';
    clearTimeout(fsHideTimer);
    fsHideTimer = setTimeout(() => {
      if (fsBtn && isFullscreenMode) fsBtn.style.opacity = '0';
    }, 2500);
  };
  fsMouseMoveHandler = showBtn;
  document.addEventListener('mousemove', showBtn);
  showBtn();
}

function clearFsAutoHide() {
  clearTimeout(fsHideTimer);
  if (fsMouseMoveHandler) {
    document.removeEventListener('mousemove', fsMouseMoveHandler);
    fsMouseMoveHandler = null;
  }
}

function loadHls() {
  // HLS.js 已通过 <script> 标签预加载
  return Promise.resolve();
}

function parsePlayUrl(u) {
  if (!u) return [];
  return u.split('#').map(e => { const [t, url] = e.split('$'); return { title: t || '', url: url || '' }; });
}

// 视频点击暂停/播放
function setupVideoClicks() {
  const v = document.getElementById('video-player');
  if (!v || v.dataset.clickSetup) return;
  v.dataset.clickSetup = '1';
  v.addEventListener('click', (e) => {
    const rect = v.getBoundingClientRect();
    const controlsHeight = 40;
    if (e.clientY > rect.bottom - controlsHeight) return;
    if (v.paused) v.play();
    else v.pause();
  });
}

async function openPlayer(vodId, title) {
  await loadHls();
  const d = await apiGet({ ids: vodId });
  if (!d?.list?.[0]) return alert('获取失败');
  const item = d.list[0];
  const eps = parsePlayUrl(item.vod_play_url);
  if (!eps.length) return alert('暂无播放源');

  // 显示播放页
  document.getElementById('app-layout').style.display = 'none';
  document.getElementById('titlebar').style.display = 'none';
  document.getElementById('player-page').style.display = 'flex';

  document.getElementById('player-title').textContent = item.vod_name;
  document.getElementById('vi-title').textContent = item.vod_name;
  document.getElementById('vi-meta').textContent = [item.vod_year, CATEGORY_NAMES[item.type_id], item.vod_area, item.vod_remarks].filter(Boolean).join(' · ');
  document.getElementById('ep-count').textContent = `${eps.length}集`;

  // 选集列表
  const eg = document.getElementById('episode-grid');
  eg.innerHTML = eps.map((ep, i) =>
    `<button class="ep-btn ${i===0?'active':''}" data-idx="${i}" data-url="${ep.url}">${ep.title}</button>`
  ).join('');

  // 先检查上次播放进度（从 localStorage 读，还没写入新记录）
  const prevHistory = getHistory().find(h => h.vodId === vodId);
  const prevEp = (prevHistory && prevHistory.epIdx !== undefined) ? prevHistory.epIdx : 0;

  // 播放第一集（或上次进度）
  const startEp = prevEp < eps.length ? prevEp : 0;
  playUrl(eps[startEp].url);
  // 高亮对应集数
  eg.querySelectorAll('.ep-btn').forEach((b, i) => {
    b.classList.toggle('active', i === startEp);
  });
  // 开始跟踪进度
  setTimeout(() => startProgressTracking(vodId, title, item.vod_pic, item.vod_remarks, startEp), 500);
  // 如果有上次进度，跳转到对应时间
  if (prevHistory && prevHistory.epTime > 10 && prevHistory.epIdx === startEp) {
    const v = document.getElementById('video-player');
    if (v) {
      v.addEventListener('loadedmetadata', function seek() {
        if (v.duration && prevHistory.epTime < v.duration) {
          v.currentTime = prevHistory.epTime;
        }
        v.removeEventListener('loadedmetadata', seek);
      }, { once: true });
    }
  }

  // 选集切换（事件委托）
  eg.onclick = e => {
    const btn = e.target.closest('.ep-btn');
    if (!btn) return;
    eg.querySelectorAll('.ep-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    const newIdx = parseInt(btn.dataset.idx);
    addHistory(vodId, title, item.vod_pic, item.vod_remarks, newIdx, 0);
    stopProgressTracking();
    playUrl(btn.dataset.url);
    setTimeout(() => startProgressTracking(vodId, title, item.vod_pic, item.vod_remarks, newIdx), 500);
  };

  // 更新历史记录
  addHistory(vodId, title, item.vod_pic, item.vod_remarks, startEp, 0);

  // 绑定返回按钮
  document.getElementById('player-back-btn').onclick = closePlayer;

  // 创建全屏按钮
  const vw = document.getElementById('video-wrapper');
  vw.style.position = 'relative';
  let fsBtn = document.getElementById('fullscreen-toggle');
  if (!fsBtn) {
    fsBtn = document.createElement('button');
    fsBtn.id = 'fullscreen-toggle';
    vw.appendChild(fsBtn);
  }
  fsBtn.textContent = '⛶';
  fsBtn.title = '全屏';
  Object.assign(fsBtn.style, {
    position: 'absolute', bottom: '48px', right: '10px', zIndex: '2100',
    width: '34px', height: '34px', background: 'rgba(0,0,0,0.55)',
    color: '#fff', border: 'none',
    borderRadius: '4px', cursor: 'pointer', fontSize: '16px',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
  });
  fsBtn.onclick = toggleFullscreen;
}

function toggleFullscreen() {
  isFullscreenMode ? exitFullscreen() : enterFullscreen();
}

async function enterFullscreen() {
  if (!window.electronAPI?.setFullScreen) return;
  await window.electronAPI.setFullScreen(true);
  isFullscreenMode = true;

  document.querySelector('.player-header').style.display = 'none';
  document.querySelector('.player-right').style.display = 'none';
  document.getElementById('video-info').style.display = 'none';

  const v = document.getElementById('video-player');
  if (v) v.style.objectFit = 'cover';

  const fsBtn = document.getElementById('fullscreen-toggle');
  if (fsBtn) { 
    fsBtn.textContent = '⊠'; fsBtn.title = '退出全屏'; 
    fsBtn.style.bottom = '10px';
    fsBtn.style.opacity = '1';
    fsBtn.style.transition = 'opacity 0.4s ease';
  }
  setupFsAutoHide(fsBtn);
}

async function exitFullscreen() {
  if (!window.electronAPI?.setFullScreen) return;
  await window.electronAPI.setFullScreen(false);
  isFullscreenMode = false;

  document.querySelector('.player-header').style.display = '';
  document.querySelector('.player-right').style.display = '';
  document.getElementById('video-info').style.display = '';
  const v = document.getElementById('video-player');
  if (v) v.style.objectFit = '';

  const fsBtn = document.getElementById('fullscreen-toggle');
  if (fsBtn) { 
    fsBtn.textContent = '⛶'; fsBtn.title = '全屏'; 
    fsBtn.style.bottom = '48px';
    fsBtn.style.opacity = '1';
    fsBtn.style.transition = '';
  }
  clearFsAutoHide();
}

function playUrl(url) {
  const v = document.getElementById('video-player');
  if (!v) return;
  setupVideoClicks();
  if (hlsInstance) { hlsInstance.destroy(); hlsInstance = null; }
  if (url.endsWith('.m3u8') && window.Hls && window.Hls.isSupported()) {
    hlsInstance = new window.Hls();
    hlsInstance.loadSource(url);
    hlsInstance.attachMedia(v);
    hlsInstance.on(window.Hls.Events.MANIFEST_PARSED, () => v.play());
  } else {
    v.src = url;
    v.play().catch(() => {});
  }
}

function closePlayer() {
  // 退出时保存最后进度
  const v = document.getElementById('video-player');
  const curEp = document.querySelector('#episode-grid .ep-btn.active');
  const epIdx = curEp ? parseInt(curEp.dataset.idx) : 0;
  // 从 history 中获取当前剧的 vodId
  const historyList = getHistory();
  if (historyList.length > 0 && v) {
    addHistory(historyList[0].vodId, historyList[0].vodName, historyList[0].vodPic, historyList[0].vodRemarks, epIdx, v.currentTime);
  }
  stopProgressTracking();
  if (hlsInstance) { hlsInstance.destroy(); hlsInstance = null; }

  if (isFullscreenMode) {
    window.electronAPI?.setFullScreen(false);
    isFullscreenMode = false;
  }

  document.querySelector('.player-header').style.display = '';
  document.querySelector('.player-right').style.display = '';
  document.getElementById('video-info').style.display = '';
  if (v) { v.pause(); v.style.objectFit = ''; v.src = ''; }

  const fsBtn = document.getElementById('fullscreen-toggle');
  if (fsBtn) { 
    fsBtn.textContent = '⛶'; fsBtn.title = '全屏'; 
    fsBtn.style.bottom = '48px';
    fsBtn.style.opacity = '1';
    fsBtn.style.transition = '';
  }
  clearFsAutoHide();

  document.getElementById('player-page').style.display = 'none';
  document.getElementById('titlebar').style.display = '';
  document.getElementById('app-layout').style.display = 'flex';
}

// ===== 历史记录 =====
const HISTORY_KEY = 'xiaofeng_history';

function getHistory() {
  try {
    return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]');
  } catch { return []; }
}

function addHistory(vodId, vodName, vodPic, vodRemarks, epIdx, epTime) {
  const list = getHistory().filter(h => h.vodId !== vodId);
  list.unshift({ vodId, vodName, vodPic, vodRemarks, epIdx, epTime: Math.floor(epTime || 0), time: Date.now() });
  if (list.length > 100) list.length = 100;
  localStorage.setItem(HISTORY_KEY, JSON.stringify(list));
}

// 定期保存播放进度（每 10 秒）
let progressTimer = null;
function startProgressTracking(vodId, vodName, vodPic, vodRemarks, epIdx) {
  stopProgressTracking();
  progressTimer = setInterval(() => {
    const v = document.getElementById('video-player');
    if (!v || !vodId) return;
    addHistory(vodId, vodName, vodPic, vodRemarks, epIdx, v.currentTime);
  }, 10000);
}
function stopProgressTracking() {
  if (progressTimer) { clearInterval(progressTimer); progressTimer = null; }
}

function loadHistory() {
  const list = getHistory();
  const gr = document.getElementById('search-results');
  if (!gr) return;
  // 更新搜索页顶部为历史标题
  const bar = document.querySelector('#page-search .search-bar');
  if (bar) {
    // 显示历史记录标题
    let title = document.getElementById('history-title');
    if (!title) {
      title = document.createElement('div');
      title.id = 'history-title';
      title.className = 'section-header';
      title.innerHTML = '<h2>📋 观看历史</h2><button class="btn" id="clear-history-btn" style="background:transparent;color:#a0a0b0;border:1px solid rgba(255,255,255,0.15);padding:4px 12px;border-radius:4px;font-size:12px;cursor:pointer">清空</button>';
      bar.parentNode.insertBefore(title, bar.nextSibling);
    }
    title.style.display = 'flex';
    bar.style.display = 'none';
    document.getElementById('clear-history-btn').onclick = () => {
      if (confirm('确定清空观看历史？')) {
        localStorage.removeItem(HISTORY_KEY);
        loadHistory();
      }
    };
  }
  if (!list.length) {
    gr.innerHTML = '<p style="color:#666;text-align:center;padding:40px">暂无观看记录</p>';
    return;
  }
  gr.innerHTML = '';
  list.forEach(i => {
    // 用简单的卡片样式
    const card = document.createElement('div');
    card.className = 'video-card';
    card.dataset.vodId = i.vodId;
    const emoji = '🎬';
    const hasPic = !!i.vodPic;
    card.innerHTML = `
      ${hasPic ? `<div class="card-thumb"><img src="${i.vodPic}" alt="" loading="lazy" onerror="this.style.display='none';this.parentElement.classList.add('thumb-fallback')"><span class="thumb-emoji">${emoji}</span></div>`
                : `<div class="card-thumb thumb-fallback"><span class="thumb-emoji">${emoji}</span></div>`}
      <div class="card-info">
        <div class="card-title" title="${i.vodName || ''}">${i.vodName || '未知'}</div>
        <div class="card-meta">${i.vodRemarks || ''}</div>
      </div>`;
    // 显示进度
    const progress = document.createElement('div');
    progress.className = 'card-remarks';
    const epInfo = i.epTitle || (i.epIdx !== undefined ? '第' + (i.epIdx+1) + '集' : '');
    const timeInfo = i.epTime ? formatTime(i.epTime) : '';
    if (epInfo || timeInfo) {
      progress.textContent = '▶ 看到 ' + [epInfo, timeInfo].filter(Boolean).join(' ');
    }
    card.querySelector('.card-info').appendChild(progress);
    card.addEventListener('click', () => openPlayer(i.vodId, i.vodName));
    gr.appendChild(card);
  });
}

function formatTime(s) {
  if (!s || s < 0) return '';
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  return m + '分' + (sec > 0 ? sec + '秒' : '');
}

// ===== 搜索 =====
async function doSearch(query) {
  if (!query) query = document.getElementById('home-search-input').value.trim();
  if (!query) return;
  // 搜索用 ac=list 绕过 WAF（ac=videolist+wd 会被拦截）
  const d = await apiGet({ ac: 'list', wd: query, pg: 1, pagesize: 40 });
  // 搜索时显示搜索栏，隐藏历史标题
  const ht = document.getElementById('history-title');
  if (ht) ht.style.display = 'none';
  const sb = document.querySelector('#page-search .search-bar');
  if (sb) sb.style.display = '';
  document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.getElementById('page-search').classList.add('active');
  const gr = document.getElementById('search-results');
  if (!d?.list || !d.list.length) {
    gr.innerHTML = '<p style="color:#666;text-align:center;padding:40px">未找到结果</p>';
  } else {
    gr.innerHTML = '';
    d.list.forEach(i => gr.appendChild(createCard(i)));
  }
}

function doSearchFromHome() {
  const q = document.getElementById('home-search-input').value.trim();
  if (!q) return;
  document.getElementById('search-input').value = q;
  doSearch(q);
}

// ===== 导航 =====
document.querySelectorAll('.nav-item').forEach(item => {
  item.addEventListener('click', async () => {
    document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
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
  console.log('绑定筛选:', bar.parentElement?.id || 'unknown');
  bar.addEventListener('click', e => {
    const t = e.target.closest('.filter-btn');
    console.log('筛选点击:', t?.textContent, 'bar:', bar.parentElement?.id);
    if (!t) return;
    bar.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
    t.classList.add('active');
    const page = bar.closest('.page');
    if (page) {
      const pid = page.id.replace('page-', '');
      const filterT = t.dataset.t || '';
      if (filterT) {
        loadCategory(pid, '', filterT);
      } else {
        loadCategory(pid, t.dataset['class'] || '');
      }
    }
  });
});

// ===== 搜索页按钮 =====
// 用更精确的选择器区分首页搜索按钮和搜索页搜索按钮
document.getElementById('page-search')?.querySelector('.btn-search')?.addEventListener('click', () => {
  const q = document.getElementById('search-input').value.trim();
  if (q) doSearch(q);
});
document.getElementById('search-input')?.addEventListener('keydown', e => {
  if (e.key === 'Enter') {
    const q = e.target.value.trim();
    if (q) doSearch(q);
  }
});
document.getElementById('home-search-input')?.addEventListener('keydown', e => {
  if (e.key === 'Enter') doSearchFromHome();
});

// ===== ESC 返回 =====
document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && document.getElementById('player-page').style.display !== 'none') {
    if (isFullscreenMode) exitFullscreen();
    else closePlayer();
  }
});

// ===== 启动 =====
loadHome();
console.log('💨 小风剧场 v3 已启动！');
