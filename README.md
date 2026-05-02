# 💨 小风剧场

> 多平台视频追剧工具 · 桌面版 + 安卓版 —— 小风 & 小齐 共建。

## 🎯 功能介绍

| 功能 | 说明 |
|:---|:---|
| 🏠 首页推荐 | 热门剧集 + 高分推荐，一键播放 |
| 🏷️ 分类浏览 | 国产剧 / 美剧 / 海外剧，支持子分类筛选 |
| 🔍 多源搜索 | 搜剧名、演员，实时从数据源获取结果 |
| 📋 观看历史 | 自动记录看过的剧，按时间倒序排列 |
| ⏯️ 进度续播 | 记住看到哪集哪分钟，下次自动跳转 |
| 🎬 在线播放 | 支持 m3u8 / mp4 格式，HLS.js 本地解码 |
| 🪟 无边框窗口 | 自定义标题栏，最小化 / 最大化 / 关闭 |
| 🖥️ 全屏沉浸 | Electron 窗口全屏，鼠标闲置 2.5s 自动隐藏控件 |

## 📦 安装

### 方式一：下载安装包

从 [Releases](https://github.com/fengse7/xiaofeng-theater/releases) 下载 `小风剧场 Setup x.x.x.exe`，双击安装。

### 方式二：从源码运行

```bash
git clone https://github.com/fengse7/xiaofeng-theater.git
cd xiaofeng-theater
npm install
npm start
```

## 🖱️ 操作说明

| 操作 | 说明 |
|:---|:---|
| 点击卡片 | 进入播放器 |
| ← 返回 | 退出播放器 |
| ⛶ 全屏按钮 | 窗口全屏播放 |
| 点击视频画面 | 暂停 / 继续播放 |
| `ESC` | 退出全屏 / 返回首页 |
| `─` `□` `✕` | 窗口最小化 / 最大化 / 关闭 |

## ⚙️ 技术栈

- **Electron 41** — 桌面跨平台框架
- **原生 HTML / CSS / JS** — 无框架依赖
- **HLS.js** — m3u8 视频解码
- **ffzy API** — 视频数据源
- **electron-builder** — 打包分发

---

## 📱 安卓版

基于 Kotlin + WebView + ExoPlayer 的原生 Android 客户端，与桌面版共享同一套前端界面。

### ✨ 特性

| 功能 | 说明 |
|:---|:---|
| 🌐 WebView 前端 | 复用桌面版 HTML/CSS/JS，移动端适配 |
| 🎬 原生播放器 | ExoPlayer 解码 HLS/m3u8 + MP4 |
| ⏩ 倍速播放 | 0.5x~2.0x 倍速，长按临时 2x |
| 👆 手势控制 | 左侧滑动亮度、右侧滑动音量、水平滑动快进 |
| 📱 底部导航 | 首页 / 国产剧 / 美剧 / 海外剧 / 历史 |
| 📋 观看历史 | localStorage 持久化存储 |
| 🔍 多源搜索 | 与桌面版相同的搜索体验 |

### 📥 下载安装

从 [Releases](https://github.com/fengse7/xiaofeng-theater/releases) 下载最新 `小风剧场-vx.x.x.apk`，直接安装即可。

> ⚠️ 需要 Android 8.0+，安装时如提示「未知来源」请允许。

### 🛠️ 从源码构建

```bash
# 1. 用 Android Studio 打开 android/ 目录
# 2. 在 android/gradle.properties 中配置你的签名信息
# 3. Build → Generate Signed APK，选择 release

# 或命令行：
cd android
gradlew assembleRelease
# APK 输出在 android/app/build/outputs/apk/release/
```

### 🏗️ 技术栈

- **Kotlin** — Android 原生开发语言
- **WebView** — 承载前端 UI
- **ExoPlayer** — HLS/mp4 视频播放
- **JavaScriptInterface** — WebView ↔ Native 通信

## ⚠️ 免责声明

1. **仅供学习交流** — 本项目为个人学习 Electron 开发的练手项目，不提供任何商业服务。
2. **资源来源** — 所有视频内容均来自第三方公开 API，与本项目无关。项目本身不存储、不缓存、不制作任何视频文件。
3. **版权归属** — 视频版权归原作者或出品方所有。若侵权请联系相关 API 源站处理。
4. **用户责任** — 用户应遵守当地法律法规，合理使用本项目。请勿用于商业用途或传播侵权内容。
5. **无保证** — 本项目按现状提供，不保证 API 源的可用性、准确性或合法性。

## 📄 开源协议

MIT License

---

💨 _小风剧场 · 一起看剧吧_  
_项目仅供学习，请支持正版内容。_
