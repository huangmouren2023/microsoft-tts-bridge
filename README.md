# Microsoft TTS Bridge

一个非官方的 Android 系统 TTS Engine 与本地 HTTP TTS Bridge，当前实现 Microsoft Translator Android-client 兼容的免费语音线路。

本项目与 Microsoft、Operit、FMB 或 FMLOCK 没有隶属关系。Translator 免费客户端线路和相关协议可能随时变化，请遵守所在地法律、Microsoft 服务条款及上游服务的使用限制。

它不修改 Operit、FMB 或 FMLOCK。普通 Android App 可以通过标准 `TextToSpeech` API 使用本应用；Operit 等调用方仍可通过原有 HTTP 接口使用。

## 系统 TTS 引擎

安装 `0.2.1` 或更高版本后，Android 的“文字转语音 / TTS 引擎”列表中会出现“Microsoft 云端语音”。应用内提供系统 TTS 设置入口、默认引擎试听按钮和临时兜底测试按钮。

在已授权 root 的调试设备上，可直接设为系统默认引擎：

```shell
adb shell su -c "settings put secure tts_default_synth com.ld.microsoftttsbridge"
adb shell settings get secure tts_default_synth
```

系统入口支持简体中文 locale、三种中文音色、语速、音调与 `stop()` 取消。Android 系统入口向微软请求 `raw-24khz-16bit-mono-pcm`，通过 `SynthesisCallback` 分块交付 24 kHz / 16-bit / mono PCM。网络分块会先按 PCM frame 对齐，避免 16-bit 样本被奇数字节分块错位后出现爆音或收音机噪音。

微软线路在尚未交付任何音频时失败，会触发 60 秒临时熔断：当前请求以及熔断窗口内的新请求明确调用另一套系统 TTS 引擎；窗口过后仍会优先重试 Microsoft。该机制不会修改 `tts_default_synth`，因此不是永久切回原引擎。若微软已开始输出 PCM 才失败，则直接报告失败，不在半句话中途拼接另一种格式和音色。

临时兜底使用 Android 标准 `synthesizeToFile()` 取得另一引擎的 WAV，再把其中的 PCM 交回当前系统 `SynthesisCallback`。缓存文件仅存在于本应用内部缓存目录，读取后立即删除；正常 Microsoft 路径不创建临时音频文件。

## 共享核心

```text
Android TextToSpeechService ─┐
                            ├─> TranslatorTtsBridge ─> Microsoft TTS
HTTP Server /tts ----------┘
```

`MicrosoftTtsApplication` 只持有一份 `TranslatorTtsBridge`。系统 TTS 与 HTTP Server 共享 token、endpoint 缓存、预热、鉴权自愈、超时和连接池；HTTP 接口继续输出 MP3，系统接口输出 PCM。

## 本地接口

- `GET /health`：返回运行状态。
- `GET /voices`：返回当前已知音色列表。
- `POST /tts`：请求 JSON，至少包含 `text`，可选 `voice` 与 `backend`；响应为 `audio/mpeg`。

示例：

```json
{"text":"你好，这是微软语音桥测试。","voice":"zh-CN-XiaochenNeural","backend":"translator"}
```

默认只绑定 `127.0.0.1:8765`。只有用户在界面明确勾选局域网访问时才绑定 `0.0.0.0`。

## Operit 配置

在 Operit 的“语音服务设置”中选择“HTTP API（远程）”：

- 方法：`POST`
- URL：`http://127.0.0.1:8765/tts`
- Content-Type：`application/json`
- 请求体：`{"text":"{text}"}`（桥默认使用 `zh-CN-XiaochenNeural`；要换音色就直接在请求体里写固定的 `voice`）
- 响应处理管线：留空

当前 Bridge 选择的是免费 Translator 线路。HTTP `/tts` 仍会明确返回微软线路错误；临时系统引擎兜底只作用于 Android 系统 TTS 入口。

## 稳定性策略

- 本地 HTTP 服务最多并发处理 4 个连接，单个微软上游请求变慢时不会阻塞后续健康检查或语音请求。
- 微软上游连接超时为 10 秒、读取超时为 25 秒、单次 HTTP 调用总超时为 30 秒。
- Translator endpoint 令牌刷新会串行复用，避免多个并发请求同时刷新同一份令牌。
- 每个本地请求都会记录最终状态、响应字节数和处理耗时，便于通过 `MicrosoftTtsBridge` 日志定位偶发故障。

本应用不注册开机广播，也没有 Root 守护。HTTP Bridge 由用户手动启动；启动后继续使用 Android 前台服务、`START_STICKY` 和部分唤醒锁维持常驻，直到用户在界面停止或手动杀进程。系统 TTS Service 则由 Android 在调用方需要朗读时按需拉起。

小米等带进程冻结机制的系统可能在前台服务仍显示运行时冻结工作线程。请把本应用的省电策略设为“不限制”。通过 ADB 管理设备时，也可以执行：

```shell
adb shell cmd deviceidle whitelist +com.ld.microsoftttsbridge
adb shell am set-inactive com.ld.microsoftttsbridge false
adb shell am set-standby-bucket com.ld.microsoftttsbridge active
```

## 构建

Windows 上使用项目自带 Wrapper，并统一使用用户级 Gradle 缓存：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = 'C:\GradleAscii'
.\gradlew.bat :app:assembleDebug
```

Release 变体：

```powershell
.\gradlew.bat :app:assembleRelease
```

产物位于 `app\build\outputs\apk\release\app-release-unsigned.apk`。仓库不内置签名密钥；本地安装或发布时请使用自己的 Android 签名配置。

## 开源协议

本项目以 MIT License 发布，见 [LICENSE](LICENSE)。
