const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');

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
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
