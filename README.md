# ARVIO Plus

**ARVIO Plus** 是基于开源项目 [ARVIO](https://github.com/ProdigyV21/ARVIO)（Apache-2.0，作者 ProdigyV21 及贡献者）的个人定制版本，
在保留 ARVIO 全部核心功能的前提下，增加了**智能双语字幕**：视频里已有两种语言的内嵌字幕时直接对齐合并（不调用 AI），只有一种时用 Gemini/Groq 翻译补全；两种语言可自由选择，并有完整的独立样式设置。

> 本仓库不是 ARVIO 官方项目，也不隶属于 ARVIO。原项目说明见 [`docs/README.upstream.md`](docs/README.upstream.md)。

- 上游仓库：https://github.com/ProdigyV21/ARVIO
- 基于的上游提交：`59c8af02f52bc26f2102224b338d28ccfca023de`（2026-10-12，`fix(simkl): gate playback sync on activities and persist checkpoints`）
- 许可证：Apache License 2.0（见 [`LICENSE`](LICENSE)），原作者版权与声明保持不变；本仓库的修改同样以 Apache-2.0 发布。

---

## 与官方 ARVIO 的区别

| 项目 | 官方 ARVIO | ARVIO Plus |
|---|---|---|
| 双语字幕 | AI 翻译用译文**替换**原文 | 智能双语：两条内嵌字幕直接合并（零 AI 调用）或 AI 补全缺失语言；主/辅语言可选，简繁区分 |
| 双语样式 | — | 中英文各自的颜色（色板 + HEX）、字号、字体/字重、描边、阴影、背景框、显示/隐藏、独立上下偏移；上下顺序、双语间距、整体位置；实时预览 |
| 包名 (applicationId) | `com.arvio.tv` | `com.arvio.tv.bilingual` —— 可与官方版**并存安装** |
| 应用名 | ARVIO | ARVIO Plus |
| 应用内自动更新 | 从官方 Releases 更新 | **已关闭**（避免下载官方 APK 覆盖/干扰），请手动安装新版 ARVIO Plus |
| 构建 | 官方 CI + 官方密钥 | 本仓库 GitHub Actions（`ARVIO Plus APK` 工作流） |

其余功能（海报墙、详情、Stremio Addons、插件/扩展、HTTP/HTTPS 播放、普通字幕、Find Best Match、Gemini/Groq 翻译与预翻译缓存等）沿用上游代码，未做改动。

---

## 智能双语字幕（两条路径）

在播放器字幕菜单 → **Bilingual Subtitles**（或 设置 → Audio & Subtitles）中打开 **Bilingual subtitles on**，
或者只对当前视频点 **Turn on bilingual for this video**。默认关闭：不开启时 ARVIO 的普通字幕与原有 AI 字幕行为完全不变。

```
视频内的文本字幕轨道（SRT / ASS / WebVTT / TX3G…）
   │
   ├─ 路径 A：主语言 + 辅助语言两条内嵌文本轨都存在
   │     主轨：正常的 Media3 TextRenderer（唯一的字幕解码器）
   │     辅轨：提取器“旁路读取”——Extractor 本来就要扫过所有轨道的数据块，
   │           顺手把未选中字幕轨的文字复制出来（无第二个播放器/视频解码器、无额外网络请求）
   │     → 按时间轴对齐（自动估计整体偏移，按重叠时长配对，支持一句对多句）
   │     → 不调用任何翻译 API（Gemini 调用次数 = 0），没有 AI Key 也能用
   │
   ├─ 路径 B：只有主语言字幕
   │     → ARVIO 原有 Gemini/Groq 翻译（批量队列、缓存、预翻译、限流）
   │     → 原文立即显示，译文到达后补上；失败时保留原文
   │
   └─ 都没有可用文本字幕 → 顶部提示原因
          ▼
   同一个双语渲染层：两种语言各自独立的颜色/字号/字体/描边/偏移、上下顺序、间距、整体位置
```

优先级：两条有效内嵌文本轨 → 直接合并 → 否则用主语言文本轨 AI 补全 → 否则明确提示。

- **对齐不可靠时绝不硬合并**：对齐器同时检查覆盖率、起点吻合度和偏移峰值；连续判定“不对应”后，
  有 AI Key 则改用 AI 翻译（顶部提示），没有则只显示主语言并提示原因。
- **尊重手动选择**：在字幕菜单里手动选了某条字幕或关闭字幕，智能模式对当前视频自动让位；
  之后再点 “Use Smart Bilingual for this video”，你选的那条会作为主语言保留。
- 每次切换都会在画面顶部短暂提示当前用的是哪条路径（例如 “merging embedded English + Chinese (Simplified) subtitles (no AI)”）。

### 三种字幕生成模式（Generation Mode）

在 **设置 → Audio & Subtitles → Bilingual Subtitles** 中配置，也可以在播放器 **Subtitles → Bilingual Subtitles** 面板中随时切换，立即生效并持久化保存。

| 模式 | 行为 |
|---|---|
| **Auto**（默认） | 两种语言的文本字幕都存在且同步可靠 → 直接合并（不调用 AI）；缺少一种 → 用 Gemini/Groq 补全。切换路径时主语言字幕保持在屏幕上，旧结果按代号丢弃，不重复翻译 |
| **Merge**（强制合并） | 只使用现有两条字幕轨道，**绝不调用 AI**；缺少语言或无法可靠对齐时显示明确提示，可在面板里手动指定“主轨 / 辅轨” |
| **AI Translate**（强制 AI） | 无论是否已有目标语言字幕，都用你指定的原语言文本轨（主语言设置或手动指定的主轨）调用 Gemini/Groq，原文与译文同显；API 失败时保留原文；未配置 Key 时提示 |

**Generation Mode 与 Display Mode 是两个独立设置**：前者决定第二语言“从哪来”，后者（Bilingual / Primary only / Secondary only / Off）只决定“显示哪几行”。

### 语言可自由配置

| 设置 | 默认 | 说明 |
|---|---|---|
| Primary subtitle language | **Auto** | 优先当前选中的文本字幕；其次与音轨语言一致的字幕；再其次英文；可手动指定 |
| Secondary subtitle language | **Chinese (Simplified)** | 可选 English、简体、繁体、日、韩、西、法、德等 |

- 支持英语+日语、日语+简体中文、英语+繁体中文等任意组合，设置持久化保存（DataStore，按 Profile）。
- 语言按 BCP 47 / ISO 639-1/2 规范化（`eng`/`en`/`English`、`jpn`/`ja`、`ger`/`deu`…）。
- **简体与繁体严格区分**：`zh-Hans`/`zh-CN`/`zh-SG` = 简体，`zh-Hant`/`zh-TW`/`zh-HK` = 繁体；
  只写 `zh`/`chi`/`zho` 的轨道会参考轨道名称（“简体”“CHS”“繁體”“Traditional”…）判断，仍无法判断时只作为最后选择并提示。
- 两种语言相同（或 Auto 解析后相同）时只显示一种并提示修改设置。
- AI 翻译缓存按目标语言分开；切换语言时正在进行的旧请求结果会被丢弃，不会串台。
- “Primary only / Secondary only” 按你配置的语言工作，不绑定英文或中文。

### 显示模式

| 模式 | 说明 |
|---|---|
| **Bilingual**（默认） | 主语言 + 辅助语言 |
| Primary only | 只显示主语言（不调用翻译） |
| Secondary only | 只显示辅助语言；未就绪/失败时临时显示主语言 |
| Off | 关闭双语层 |

其他保证：

- 翻译尚未返回时先显示主语言，失败/超时/限流时保留主语言，不会整屏无字幕。
- 拖动进度条、暂停、切换字幕轨道、切换片源或剧集时，旧的异步结果按“代号”丢弃；换片源时旁路字幕数据整体清空。
- 交换上下顺序只改变布局，不会重新翻译。
- 普通（非双语）字幕轨道仍由 ARVIO 原有的 SubtitleView 渲染。

---

## 如何启用 Gemini AI 翻译

1. 打开 **Settings → AI Subtitles**（ARVIO 原有设置），选择模型（Gemini 或 Groq）并填入你的 API Key。
   ARVIO Plus 是独立安装的应用，需要在其中重新填写一次 Key（官方版中的设置不会自动带过来）。
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
3. 在 ARVIO Plus 中重新登录/添加插件和 Addons（两个应用的数据互相独立）。

**签名说明**：当前构建使用为本仓库生成的**测试用途签名密钥**（`*-stable-signed.apk`），只保存在仓库 Actions Secrets 中，
后续通过同一工作流构建的新版可以直接覆盖升级。若仓库 Secrets 中的密钥丢失或被更换，新 APK 将无法覆盖安装，需要先卸载旧版
（会丢失本应用内的设置）。文件名带 `-debug-signed` 的 APK 是临时调试签名，**不能**用于后续升级。

---

## 通过 GitHub Actions 重新编译

1. 仓库 → **Actions** → **ARVIO Plus APK** → **Run workflow**（可勾选 *create_release* 同时发布 Release）。
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

- 路径 A（双轨直接合并）需要两条字幕都**内嵌在同一个 MKV/MP4/WebM 文件**里并且是文本字幕；HLS/DASH 流和外挂（Addon）字幕不会被自动用作辅助语言（会走路径 B）。
- PGS/VobSub 图片字幕既不能合并也不能翻译。
- 对齐要先读到几句字幕才能确认偏移：开头几秒只配对时间上本来就重合的句子。
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
