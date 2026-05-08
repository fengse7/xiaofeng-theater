const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  // 标题栏操作
  minimize: () => ipcRenderer.send('window-minimize'),
  maximize: () => ipcRenderer.send('window-maximize'),
  close: () => ipcRenderer.send('window-close'),

  // 窗口全屏（视频全屏用）
  setFullScreen: (flag) => ipcRenderer.invoke('set-fullscreen', flag),

  // 窗口状态监听（最大化/还原变化）
  onWindowStateChange: (callback) => {
    ipcRenderer.on('window-state-changed', (_event, state) => callback(state));
  },

  // 全屏状态监听
  onFullScreenChange: (callback) => {
    ipcRenderer.on('fs-state-changed', (_event, state) => callback(state));
  },

  // 下载功能
  downloadEpisode: (vodName, epTitle, url) =>
    ipcRenderer.invoke('download-episode', vodName, epTitle, url),
  getDownloadDir: () => ipcRenderer.invoke('get-download-dir'),
  openDownloadDir: () => ipcRenderer.invoke('open-download-dir'),

  // 下载进度监听
  onDownloadProgress: (callback) => {
    ipcRenderer.on('download-progress', (_event, data) => callback(data));
  },
});
