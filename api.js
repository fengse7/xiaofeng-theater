// ===== 视频数据源：ffzy（飞资源）API =====
const API_BASE = 'https://cj.ffzyapi.com/api.php/provide/vod/from/ffm3u8';

// 分类映射：TVBox分类ID → 我们的页面
const CATEGORY_MAP = {
  domestic: [13, 14, 21],      // 国产剧、香港剧、台湾剧
  us: [16],                     // 欧美剧
  international: [15, 22, 23, 24], // 韩日海外泰
};

// 分类名称映射
const CATEGORY_NAMES = {
  1: '电影片', 2: '连续剧', 3: '综艺片', 4: '动漫片',
  6: '动作片', 7: '喜剧片', 8: '爱情片', 9: '科幻片',
  10: '恐怖片', 11: '剧情片', 12: '战争片',
  13: '国产剧', 14: '香港剧', 15: '韩国剧',
  16: '欧美剧', 20: '记录片', 21: '台湾剧',
  22: '日本剧', 23: '海外剧', 24: '泰国剧',
  25: '大陆综艺', 26: '港台综艺', 27: '日韩综艺',
  28: '欧美综艺', 29: '国产动漫', 30: '日韩动漫',
  31: '欧美动漫', 32: '港台动漫', 33: '海外动漫',
  34: '伦理片', 36: '短剧',
};

// 调用API获取数据
async function fetchAPI(params) {
  const url = API_BASE + '?' + new URLSearchParams(params).toString();
  try {
    const resp = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0' } });
    const data = await resp.json();
    return data;
  } catch (e) {
    console.error('API请求失败:', e);
    return null;
  }
}

// 获取分类列表
async function getCategories() {
  const data = await fetchAPI({ ac: 'list', pg: 1, pagesize: 1 });
  if (data && data.class) {
    return data.class;
  }
  return [];
}

// 获取某分类的视频列表
async function getVideos(typeId, page = 1, pageSize = 40) {
  const data = await fetchAPI({ ac: 'list', t: typeId, pg: page, pagesize: pageSize });
  if (data && data.list) {
    return { list: data.list, total: data.total, pagecount: data.pagecount };
  }
  return { list: [], total: 0, pagecount: 0 };
}

// 获取视频详情（含播放链接）
async function getVideoDetail(vodId) {
  const data = await fetchAPI({ ac: 'detail', ids: vodId });
  if (data && data.list && data.list.length > 0) {
    return data.list[0];
  }
  return null;
}

// 解析播放链接字符串 → 数组
// 格式: "第01集$https://...m3u8#第02集$https://...m3u8"
function parsePlayUrl(playUrl) {
  if (!playUrl) return [];
  return playUrl.split('#').map(episode => {
    const [title, url] = episode.split('$');
    return { title: title || '', url: url || '' };
  });
}

// 搜索
async function searchVideos(keyword, page = 1) {
  const data = await fetchAPI({ ac: 'list', wd: keyword, pg: page, pagesize: 40 });
  if (data && data.list) {
    return { list: data.list, total: data.total, pagecount: data.pagecount };
  }
  return { list: [], total: 0, pagecount: 0 };
}
