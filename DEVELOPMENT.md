# ARVIO Bilingual — Development notes

Base: upstream ARVIO `59c8af02f52bc26f2102224b338d28ccfca023de` (2026-10-12).

## Why an overlay and not a second subtitle track

Media3 1.9 renders text through `TextRenderer` → `TextOutput.onCues(CueGroup)` → player listeners →
`PlayerView.SubtitleView`. `SubtitleView` takes one global `CaptionStyleCompat` (one edge type, one
edge colour, one typeface) for all cues; per-cue colour/size spans only work with embedded styles
enabled and outline/shadow/offset can't differ per language. Loading a second track would need a
second `TextRenderer` *and* a second selected text track — Media3 selects at most one text track per
renderer and the second one would come from the same file anyway.

So ARVIO Bilingual keeps **one** source track and **one** `TextRenderer`:

1. `AiSubtitleRenderersFactory.TranslatingTextOutput.onCues(CueGroup)` — when AI translation is
   active and the cue group carries text, it hands `SubtitleView` an empty group and publishes a
   `BilingualFrame(original, translation?)` to `SubtitleTranslationManager.bilingualBus`.
   Image cues (PGS) and the non-AI path are passed through unchanged.
2. `BilingualSubtitleOverlay` (Compose, placed right above the `PlayerView` in `PlayerScreen`) draws the
   frame as two independently styled text blocks. Because both blocks come from the same cue
   callback, they appear/disappear on exactly the source cue timeline; the existing renderer-level
   subtitle delay / auto-sync offset (`SubtitleOffsetRenderer`) still applies.

No extra decoder, no extra network request, and style/position changes are just recomposition.

## Async / staleness model

`BilingualCueBus` (thread-safe):

- `showCue()` increments a generation counter and shows the source text immediately (with the
  cached translation if there is one) — returns a token.
- the translation coroutine calls `deliverTranslation(token, sourceKey, …)`; it is applied **only** if
  the frame still has that generation and key. Any new cue, empty cue (end of subtitle / seek), SDH-only
  cue, image cue, `manager.reset()` (new media / source / episode) or `isEnabled = false` (track switch,
  AI off) increments the generation, so late results are dropped.
- failure (`translateDetailed` reports `ok=false` when the existing queue fell back to the source text
  without caching) marks the frame failed → English stays.

Translation requests go through the unchanged ARVIO queue: `translate()` joins an in-flight request
for the same text and the 2-minute pre-translation window fills the same cache, so bilingual mode
never duplicates requests. `displayMode` ORIGINAL_ONLY/OFF disables both the real-time request and the
lookahead (`translationNeeded`).

## Layout

`BilingualLayout.place()` (pure, unit-tested) stacks the measured blocks bottom-up from
`bottomPercent` of the video height, applies the gap and per-block offsets, prevents overlap and clamps
the group into the safe area (display cut-outs are excluded with `WindowInsets.displayCutout`). Text is
measured at 92 % of the width so long Chinese lines wrap instead of being cut.

## Settings persistence

`BilingualSubtitleSettingsStore` (Hilt singleton, `BilingualSubtitleModule`) keeps a `StateFlow` that
updates synchronously (live preview while dragging) and persists JSON
(`BilingualSettingsCodec`, tolerant decode + clamping) to the existing `settings_prefs` DataStore under
`profile_<profileId>_bilingual_subtitle_settings_v1`. It is not in `CloudSyncRepository`'s key list, so
cloud sync payloads are unchanged.

## Files

New (all in `app/src/main/kotlin/com/arflix/tv/ui/screens/player/subtitles/bilingual/`):

| File | Purpose |
|---|---|
| `BilingualSubtitleSettings.kt` | settings model, defaults, JSON codec, colour helpers |
| `BilingualCueBus.kt` | frame bus + staleness rules; `BilingualComposer` (what to show per mode/order/visibility) |
| `BilingualLayout.kt` | pure vertical layout maths |
| `BilingualSubtitleSettingsStore.kt` | persistence + Hilt module / entry point |
| `BilingualSubtitleOverlay.kt` | Compose overlay + outlined text rendering (also used by the preview) |
| `BilingualSubtitleSettingsPanel.kt` | settings UI (basic + advanced, live preview, hints) |
| `BilingualSettingsEntry.kt` | Settings → Audio & Subtitles entry (full-screen dialog) |

Modified upstream files:

| File | Change |
|---|---|
| `player/engine/exoplayer/AiSubtitleRenderersFactory.kt` | AI path publishes to the bus instead of rewriting cue text; lookahead respects display mode |
| `player/subtitles/SubtitleTranslationManager.kt` | `bilingualBus`, `displayMode`, `translationNeeded`, `translateDetailed`; clear on reset/disable |
| `player/PlayerScreen.kt` | mounts `BilingualSubtitleOverlay` above the PlayerView |
| `player/mobile/MobilePlayerPanels.kt` | "Bilingual AI Subtitles" entry + page in the Subtitles sheet |
| `settings/SettingsScreen.kt` | entry in Audio & Subtitles (mobile) |
| `app/build.gradle.kts` | `applicationId com.arvio.tv.bilingual`, version suffix, self-update off |
| `res/values*/strings.xml` | app name "ARVIO Bilingual" |
| `.github/workflows/bilingual-apk.yml` | new APK workflow (upstream workflows are disabled in this repo via the Actions UI, files untouched) |

Tests: `app/src/test/kotlin/com/arflix/tv/ui/screens/player/subtitles/bilingual/`.

```bash
./gradlew :app:testSideloadDebugUnitTest --tests 'com.arflix.tv.ui.screens.player.subtitles.bilingual.*'
```
