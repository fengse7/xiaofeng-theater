const { app, BrowserWindow, ipcMain, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const https = require('https');
const http = require('http');

function createWindow() {
  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true,
      webSecurity: false,
    },
    frame: false,
    thickFrame: false,
    hasShadow: false,
    backgroundColor: '#000000',
    show: false,
  });

  // 准备好后再显示，避免白屏闪烁
  win.once('ready-to-show', () => win.show());

  // 最大化/还原通知渲染进程（更新方框图标）
  win.on('maximize', () => {
    win.webContents.send('window-state-changed', { maximized: true });
  });
  win.on('unmaximize', () => {
    win.webContents.send('window-state-changed', { maximized: false });
  });

  // 全屏状态变化通知渲染进程
  win.on('enter-full-screen', () => {
    win.webContents.send('fs-state-changed', { fullscreen: true });
  });
  win.on('leave-full-screen', () => {
    win.webContents.send('fs-state-changed', { fullscreen: false });
  });

  win.loadFile('index.html');

  // 开发者工具（调试时可取消注释）
  // win.webContents.openDevTools();
}

// ===== 下载工具函数 =====

// 安全文件名
function safeFilename(name) {
  return (name || '').replace(/[\\/:*?"<>|]/g, '_').trim();
}

// HTTP 请求封装（支持 redirect）
function httpGet(url, maxRedirects = 5) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const lib = u.protocol === 'https:' ? https : http;
    lib.get(url, { headers: { 'User-Agent': 'Mozilla/5.0', 'Referer': 'https://cj.ffzyapi.com/' } }, (res) => {
      // 处理重定向
      if ((res.statusCode === 301 || res.statusCode === 302 || res.statusCode === 307 || res.statusCode === 308) && maxRedirects > 0 && res.headers.location) {
        const redirectUrl = res.headers.location.startsWith('http') ? res.headers.location : new URL(res.headers.location, url).href;
        return httpGet(redirectUrl, maxRedirects - 1).then(resolve, reject);
      }
      resolve(res);
    }).on('error', reject);
  });
}

// 下载单个文件（带进度）
function downloadFile(url, vodName, epTitle, ext, win, downloadDir) {
  return new Promise((resolve, reject) => {
    const filename = safeFilename(`${vodName} - ${epTitle}${ext}`);
    const filePath = path.join(downloadDir, filename);

    httpGet(url).then((res) => {
      const totalLen = parseInt(res.headers['content-length'] || '0', 10);
      const ws = fs.createWriteStream(filePath);
      let downloaded = 0;

      res.on('data', (chunk) => {
        downloaded += chunk.length;
        const pct = totalLen > 0 ? Math.round((downloaded / totalLen) * 100) : 0;
        if (win && !win.isDestroyed()) {
          win.webContents.send('download-progress', { filename, pct, downloaded, total: totalLen, status: 'downloading' });
        }
      });

      ws.on('finish', () => {
        if (win && !win.isDestroyed()) {
          win.webContents.send('download-progress', { filename, pct: 100, downloaded, total: totalLen, status: 'done', filePath });
        }
        resolve({ success: true, filePath, filename, size: downloaded });
      });

      res.pipe(ws);
    }).catch(reject);
  });
}

// 解析 m3u8 内容
function parseM3u8(content, baseUrl) {
  const lines = content.split(/\r?\n/);
  const segments = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (line && !line.startsWith('#')) {
      // 处理相对 URL
      let segUrl = line;
      if (!segUrl.startsWith('http')) {
        try {
          segUrl = new URL(segUrl, baseUrl).href;
        } catch (e) {
          segUrl = line;
        }
      }
      segments.push(segUrl);
    }
  }
  return segments;
}

// 下载 m3u8（含所有分片）
async function downloadM3u8(url, vodName, epTitle, win, downloadDir) {
  const filename = safeFilename(`${vodName} - ${epTitle}.ts`);
  const filePath = path.join(downloadDir, filename);

  // 1. 下载 m3u8 播放列表
  const playlistRes = await httpGet(url);
  const playlist = await new Promise((resolve, reject) => {
    let data = '';
    playlistRes.on('data', c => data += c);
    playlistRes.on('end', () => resolve(data));
    playlistRes.on('error', reject);
  });

  // 2. 解析分片
  const segments = parseM3u8(playlist, url);
  if (!segments.length) {
    // 可能是嵌套 m3u8（有不同码率的子列表）
    const m3u8Match = playlist.match(/^[^#].*\.m3u8$/m);
    if (m3u8Match) {
      const subUrl = new URL(m3u8Match[0].trim(), url).href;
      return downloadM3u8(subUrl, vodName, epTitle, win, downloadDir);
    }
    throw new Error('无法解析 m3u8 分片');
  }

  // 3. 逐个下载分片并合并
  const ws = fs.createWriteStream(filePath);
  let totalDownloaded = 0;
  const totalSegments = segments.length;

  for (let i = 0; i < segments.length; i++) {
    const segUrl = segments[i];
    try {
      const segRes = await httpGet(segUrl);
      const segData = await new Promise((resolve, reject) => {
        const chunks = [];
        segRes.on('data', c => chunks.push(c));
        segRes.on('end', () => resolve(Buffer.concat(chunks)));
        segRes.on('error', reject);
      });

      ws.write(segData);
      totalDownloaded += segData.length;

      const pct = Math.round(((i + 1) / totalSegments) * 100);
      if (win && !win.isDestroyed()) {
        win.webContents.send('download-progress', {
          filename, pct, downloaded: totalDownloaded,
          total: totalSegments, status: 'downloading',
          currentSegment: i + 1, totalSegments
        });
      }
    } catch (e) {
      console.error(`分片 ${i + 1}/${totalSegments} 下载失败:`, e.message);
    }
  }

  // 等待写入完成
  await new Promise((resolve, reject) => {
    ws.end(() => resolve());
    ws.on('error', reject);
  });

  if (win && !win.isDestroyed()) {
    win.webContents.send('download-progress', { filename, pct: 100, status: 'done', filePath });
  }

  return { success: true, filePath, filename, size: totalDownloaded };
}

app.whenReady().then(() => {
  createWindow();

  // 标题栏 IPC：最小化
  ipcMain.on('window-minimize', (event) => {
    BrowserWindow.fromWebContents(event.sender)?.minimize();
  });

  // 标题栏 IPC：最大化/还原
  ipcMain.on('window-maximize', (event) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    if (!win) return;
    if (win.isMaximized()) {
      win.unmaximize();
    } else {
      win.maximize();
    }
  });

  // 标题栏 IPC：关闭
  ipcMain.on('window-close', (event) => {
    BrowserWindow.fromWebContents(event.sender)?.close();
  });

  // 窗口全屏（用于视频全屏，代替 HTML5 fullscreen API 以解决白边）
  ipcMain.handle('set-fullscreen', (event, flag) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    if (win) win.setFullScreen(flag);
  });

  // ===== 下载功能 =====
  const DOWNLOAD_DIR = path.join(app.getPath('downloads'), '小风剧场');
  if (!fs.existsSync(DOWNLOAD_DIR)) fs.mkdirSync(DOWNLOAD_DIR, { recursive: true });

  // 获取下载目录
  ipcMain.handle('get-download-dir', () => DOWNLOAD_DIR);

  // 打开下载目录
  ipcMain.handle('open-download-dir', () => shell.openPath(DOWNLOAD_DIR));

  // 下载单集
  ipcMain.handle('download-episode', async (event, vodName, epTitle, url) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    const safeName = (vodName || '未知').replace(/[\\/:*?"<>|]/g, '_');
    const safeEp = (epTitle || '第1集').replace(/[\\/:*?"<>|]/g, '_');

    try {
      if (url.endsWith('.m3u8')) {
        return await downloadM3u8(url, safeName, safeEp, win, DOWNLOAD_DIR);
      } else {
        return await downloadFile(url, safeName, safeEp, '.mp4', win, DOWNLOAD_DIR);
      }
    } catch (err) {
      return { success: false, error: err.message };
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
