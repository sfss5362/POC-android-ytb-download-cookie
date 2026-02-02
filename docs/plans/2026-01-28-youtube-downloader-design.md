# Android YouTube Downloader 设计文档

## 概述

Android 应用，用于解析和下载 YouTube 视频。支持机器人验证时通过 WebView 登录获取 cookies。

## 技术选型

- **语言**: Java
- **构建工具**: Gradle
- **最低 API**: 24 (Android 7.0)
- **核心依赖**: java-youtube-downloader---fix-cookies

## 架构

```
┌─────────────────────────────────────────────────────┐
│                    MainActivity                      │
│  - URL 输入框 + 解析按钮                             │
│  - 视频信息卡片                                      │
│  - 格式选择                                          │
│  - 下载列表 RecyclerView                            │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│              CookieWebViewActivity                   │
│  - 检测到机器人验证时弹出                            │
│  - 登录后自动获取 cookies                           │
└─────────────────────────────────────────────────────┘
```

## 核心模块

- **YoutubeService** - 封装下载库，处理解析/下载
- **CookieStorage** - 管理 cookies 持久化
- **DownloadService** - 后台下载服务
- **FFmpegHelper** - 音视频合并

## 机器人验证处理流程

1. 用户输入 URL
2. 尝试解析视频
3. 如果返回 "Sign in to confirm you're not a bot"
4. 启动 WebView 让用户登录
5. 登录成功后提取 cookies (SAPISID, SID, HSID, SSID)
6. 用 cookies 重新解析

## 下载模式

1. **仅视频** - 直接下载带音频格式
2. **仅音频** - 下载最高音质
3. **最佳画质合并** - 下载视频+音频，FFmpeg 合并

## 项目结构

```
app/src/main/java/com/example/ytdownloader/
├── MainActivity.java
├── CookieWebViewActivity.java
├── service/
│   ├── YoutubeService.java
│   └── DownloadService.java
├── manager/
│   ├── CookieStorage.java
│   └── FFmpegHelper.java
├── model/
│   ├── VideoInfo.java
│   └── DownloadTask.java
└── adapter/
    └── DownloadListAdapter.java
```

## 依赖

```groovy
implementation 'com.github.sfss5362:java-youtube-downloader---fix-cookies:TAG'
implementation 'com.arthenica:mobile-ffmpeg-min:4.4.LTS'
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
implementation 'com.github.bumptech.glide:glide:4.16.0'
```

## 权限

- INTERNET
- FOREGROUND_SERVICE
- POST_NOTIFICATIONS
