# ARVIO Bilingual

**ARVIO Bilingual** 是基于开源项目 [ARVIO](https://github.com/ProdigyV21/ARVIO)（Apache-2.0，作者 ProdigyV21 及贡献者）的个人定制版本，
在保留 ARVIO 全部核心功能的前提下，为 **AI 字幕翻译** 增加了真正的**英中双语同屏显示**和完整的双语字幕样式设置。

> 本仓库不是 ARVIO 官方项目，也不隶属于 ARVIO。原项目说明见 [`docs/README.upstream.md`](docs/README.upstream.md)。

- 上游仓库：https://github.com/ProdigyV21/ARVIO
- 基于的上游提交：`59c8af02f52bc26f2102224b338d28ccfca023de`（2026-10-12，`fix(simkl): gate playback sync on activities and persist checkpoints`）
- 许可证：Apache License 2.0（见 [`LICENSE`](LICENSE)），原作者版权与声明保持不变；本仓库的修改同样以 Apache-2.0 发布。

---

## 与官方 ARVIO 的区别

| 项目 | 官方 ARVIO | ARVIO Bilingual |
|---|---|---|
| AI 翻译字幕 | 用译文**替换**原文 | 原文与译文**同屏双语**（也可只显示原文/只显示译文/关闭） |
| 双语样式 | — | 中英文各自的颜色（色板 + HEX）、字号、字体/字重、描边、阴影、背景框、显示/隐藏、独立上下偏移；上下顺序、双语间距、整体位置；实时预览 |
| 包名 (applicationId) | `com.arvio.tv` | `com.arvio.tv.bilingual` —— 可与官方版**并存安装** |
| 应用名 | ARVIO | ARVIO Bilingual |
| 应用内自动更新 | 从官方 Releases 更新 | **已关闭**（避免下载官方 APK 覆盖/干扰），请手动安装新版 ARVIO Bilingual |
| 构建 | 官方 CI + 官方密钥 | 本仓库 GitHub Actions（`ARVIO Bilingual APK` 工作流） |

其余功能（海报墙、详情、Stremio Addons、插件/扩展、HTTP/HTTPS 播放、普通字幕、Find Best Match、Gemini/Groq 翻译与预翻译缓存等）沿用上游代码，未做改动。

---

## 双语字幕功能

工作流程：

```
视频内嵌英文文本字幕 (SRT / ASS/SSA / WebVTT / TX3G…，Media3 可解码的文本字幕)
        │  同一条 Media3 字幕轨道、同一个 TextRenderer（不加载第二条轨道/第二个解码器）
        ▼
ARVIO 现有 SubtitleTranslationManager（批量队列、缓存、2 分钟预翻译窗口）
        │  Gemini / Groq（沿用 App 内已配置的 API Key）
        ▼
双语字幕层（Compose）：英文原文 + 简体中文译文，严格使用原字幕的时间轴
```

- 默认：**英文在上（白色 #FFFFFF，略小），中文在下（暖黄 #F4D979，略大、加粗）**，两者都是黑色细描边 + 阴影，居中。
- 翻译尚未返回时先显示英文，中文一到立即补上；翻译失败/超时/限流时保留英文，不会出现字幕消失。
- 同一时刻多条字幕 Cue、多行字幕会合并成一个块显示，自动换行，两块之间不会重叠，也不会被推出屏幕。
- 拖动进度条、切换字幕轨道、切换片源或剧集时，旧的异步翻译结果会被丢弃（每条字幕有独立的代号），不会“串台”。
- 相同文本只翻译一次：实时路径与预翻译共享 ARVIO 原有的缓存和“进行中”请求，双语模式不会增加 API 请求。
- “仅原文 / 关闭”模式下不发送任何翻译请求。
- 普通（非 AI）字幕轨道仍由 ARVIO 原有的 SubtitleView 渲染，样式与行为与官方一致。

### 显示模式

| 模式 | 说明 |
|---|---|
| **Bilingual**（默认） | 英文 + 中文 |
| Original only | 只显示英文原文（不调用翻译） |
| Translation only | 只显示中文；译文未就绪/失败时临时显示英文 |
| Off | 关闭 AI 字幕 |

---

## 如何启用 Gemini AI 翻译

1. 打开 **Settings → AI Subtitles**（ARVIO 原有设置），选择模型（Gemini 或 Groq）并填入你的 API Key。
   ARVIO Bilingual 是独立安装的应用，需要在其中重新填写一次 Key（官方版中的设置不会自动带过来）。
2. 在 **Settings → Languages → Default Subtitle** 选择 **Chinese（简体中文）**——ARVIO 的 AI 翻译目标语言就是首选字幕语言。
3. 播放带有**英文内嵌文本字幕**的视频，打开播放器 **Subtitles** 面板，打开 **AI Translation (Chinese)**。
   （也可以开启 ARVIO 原有的 *Auto-Select AI Translation*，没有中文字幕时自动启用。）
4. 默认即为双语显示。

没有可翻译的英文文本字幕（例如只有 PGS 图片字幕）或没有配置 API Key 时，双语设置面板顶部会显示明确提示。

---

## 调整中英文颜色、位置、大小及间距

入口（任一处修改都会立即生效并自动保存）：

- **播放器内**：Subtitles 面板 → **Bilingual AI Subtitles**（Subtitles 首页和 Subtitle Settings 页都有入口）
- **全局设置**：Settings → **Audio & Subtitles** → **Bilingual AI Subtitles**

**基础设置**：显示模式；上下顺序（English on top / Chinese on top）；双语间距（0–48 dp）；整体位置（距底部 0–60%）；英文/中文字号；英文/中文颜色（色板 + HEX，可输入 `#RRGGBB` 或 `#AARRGGBB`）。

**高级设置**（打开 *Advanced settings*）：中英文分别设置 显示/隐藏、独立垂直偏移（±120 dp）、加粗、字体（复用 ARVIO 字幕字体）、描边（无/细/中/粗）及描边颜色、阴影、背景框。

顶部有实时预览区域。所有设置保存在 ARVIO 原有的 DataStore（按 Profile 区分），退出播放器、重启 App、切换影片后依然有效。
双语设置**不参与云同步**（没有修改 ARVIO 的云同步协议）。

---

## 安装 APK

1. 打开本仓库的 **Releases**（或 Actions 运行记录中的 Artifact），下载 `ARVIO-Bilingual-…-signed.apk`。
   Artifact 下载后是一个 zip，解压得到 APK。
2. 在手机上允许“安装未知来源应用”，安装 APK。它的包名是 `com.arvio.tv.bilingual`，会和官方 ARVIO 并存，不会覆盖官方版。
3. 在 ARVIO Bilingual 中重新登录/添加插件和 Addons（两个应用的数据互相独立）。

**签名说明**：当前构建使用为本仓库生成的**测试用途签名密钥**（`*-stable-signed.apk`），只保存在仓库 Actions Secrets 中，
后续通过同一工作流构建的新版可以直接覆盖升级。若仓库 Secrets 中的密钥丢失或被更换，新 APK 将无法覆盖安装，需要先卸载旧版
（会丢失本应用内的设置）。文件名带 `-debug-signed` 的 APK 是临时调试签名，**不能**用于后续升级。

---

## 通过 GitHub Actions 重新编译

1. 仓库 → **Actions** → **ARVIO Bilingual APK** → **Run workflow**（可勾选 *create_release* 同时发布 Release）。
2. 完成后在运行页面底部 **Artifacts** 下载 APK；推送形如 `v2.2-bilingual.2` 的 tag 也会自动构建并发布 Release。

可选的仓库 Secrets（Settings → Secrets and variables → Actions）：

| Secret | 作用 |
|---|---|
| `TMDB_API_KEY` | **海报墙/影片信息所需**的 TMDB v3 Key（themoviedb.org 免费申请）。未设置时目录与详情无法加载。 |
| `BILINGUAL_KEYSTORE_BASE64` / `BILINGUAL_KEYSTORE_PASSWORD` / `BILINGUAL_KEY_ALIAS` / `BILINGUAL_KEY_PASSWORD` | 稳定签名密钥 |
| `TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET` / `MDBLIST_CLIENT_ID` / `SIMKL_CLIENT_ID` | 可选的追踪服务 |

CI 不需要任何 Gemini/Groq Key；密钥只通过 Secrets 注入，不会写入代码或日志。
本地构建：JDK 17 + Android SDK 36，`cp secrets.defaults.properties secrets.properties` 后运行
`./gradlew :app:assembleSideloadRelease`（sideload 变体包含插件功能）。

---

## 从 upstream 同步 ARVIO 更新

```bash
git remote add upstream https://github.com/ProdigyV21/ARVIO.git   # 首次
git fetch upstream
git checkout main
git merge upstream/main          # 或 git rebase upstream/main
# 解决冲突（通常集中在 AiSubtitleRenderersFactory.kt、MobilePlayerPanels.kt、PlayerScreen.kt）
./gradlew :app:testSideloadDebugUnitTest --tests 'com.arflix.tv.ui.screens.player.subtitles.bilingual.*'
git push origin main
```

本仓库的修改集中在 `app/src/main/kotlin/com/arflix/tv/ui/screens/player/subtitles/bilingual/`，对上游文件只做了少量插入，
便于合并。详见 [`DEVELOPMENT.md`](DEVELOPMENT.md)。

---

## 已知问题与限制

- 需要视频内嵌（或 ARVIO 能选到的）**英文文本字幕**；PGS/VobSub 图片字幕无法翻译。
- 双语模式下，ASS/SSA 的特效位置、逐字颜色等内嵌样式不会保留，统一以底部居中的双语块显示（普通字幕模式不受影响）。
- 翻译质量与速度取决于 Gemini/Groq；免费额度限流时会暂时只显示英文。
- 自带构建没有官方的 TMDB/Trakt/Google 等私有凭据：未配置 `TMDB_API_KEY` 时海报墙不可用；**Google 一键登录在本构建中不可用**
  （需要官方的 OAuth 客户端与签名）；Discord Rich Presence（官方私有 SDK）不包含在内。
- 两个应用都注册了 `arvio://`、`stremio://` 链接，打开此类链接时 Android 可能会让你选择用哪个应用打开。
- 应用内自动更新已关闭。
- 双语设置目前只在手机端界面提供；Android TV 端会显示双语字幕（使用已保存的设置），但未添加 TV 遥控器专用的设置界面。
- 尚未在真机上完成验证的项目见最新 Release 说明 / 验收报告。

---

## 致谢

ARVIO © ProdigyV21 and contributors — Apache License 2.0. Media3/ExoPlayer © The Android Open Source Project — Apache License 2.0.
