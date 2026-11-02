# Recode

一个面向骁龙 Android 设备的 FFmpeg 转码工具，定位接近 HandBrake：重编码视频和音频、剪切片段、换封装格式。

## 功能

- **硬件编码**：骁龙 MediaCodec H.264 / HEVC（以及支持的机型上的 AV1），启动时会实际试编码，确认每个编码器在这台设备上真的能用
- **软件编码**：x264、x265（含 10-bit）、VP9，以及构建里包含的 AV1 编码器
- **预设**：内置 8 套常用预设，也可以保存自己的
- **剪切**：带预览和缩略图时间轴，可以剪多段并自动拼接；直接复制模式下无损快剪
- **画面**：缩放（按短边，不放大）、裁剪比例、旋转、翻转、帧率、HDR → SDR 色调映射
- **音频**：AAC / Opus / MP3 / AC-3 / FLAC / PCM，声道、采样率、EBU R128 响度标准化、多音轨
- **字幕**：自动转换成目标格式能放的字幕（mov_text / SRT / WebVTT），放不下的会提示
- **任务队列**：后台前台服务逐个处理，Android 16+ 显示为实时通知（状态栏胶囊和分段进度条），完成后发通知
- **输出**：通过 MediaStore 保存到「影片/Recode」或「音乐/Recode」，不需要存储权限
- 支持从相册或文件管理器「分享到 Recode」

## 质量保证

`app/src/test` 里的单元测试覆盖命令生成、ffprobe 解析、参数校验和设置联动。`RealFfmpegTest` 会在 CI 上把 App 能生成的每一类命令（所有预设、单段和多段剪切、裁剪旋转、HDR 色调映射、各种封装格式等）交给真实的 FFmpeg 跑一遍，再用 ffprobe 检查输出的时长、分辨率和编码。

## 构建

```
./gradlew assembleRelease
```

GitHub Actions 每次推送到 `main` 都会运行测试、构建 APK，并发布成 Release，可以直接在手机上下载安装。

如果要使用自己的发布签名，在仓库 Secrets 里设置 `RECODE_KEYSTORE_B64`（keystore 文件的 base64）、`RECODE_KEYSTORE_PASSWORD`、`RECODE_KEY_ALIAS` 和 `RECODE_KEY_PASSWORD`。没有设置时，使用仓库里的调试密钥签名。

## 许可证

GPL-3.0。本应用通过 FFmpegKit（社区维护版）使用 FFmpeg，并包含 x264、x265 等 GPL 组件。
