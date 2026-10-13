package com.arflix.tv.ui.screens.player.subtitles.bilingual

import java.util.concurrent.atomic.AtomicLong

/** A subtitle the smart mode may use: one of the player's subtitle entries. */
data class SubtitleCandidate(
    /** The player's subtitle id (what gets selected). */
    val id: String,
    val languageTag: String?,
    val label: String?,
    val isEmbedded: Boolean,
    val isBitmap: Boolean,
    val isForced: Boolean,
    /** Its track inside [EmbeddedSubtitleStore] when the tap has seen it (null = text not readable off-track). */
    val tapTrackId: Int? = null,
    val isDefault: Boolean = false,
) {
    val language: SubtitleLanguage? get() = SubtitleLanguage.ofTrack(languageTag, label)
    val isText: Boolean get() = !isBitmap
    val looksSdh: Boolean get() = label?.let { Regex("(^|\\W)(sdh|cc|hi)(\\W|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true
}

/** What the smart bilingual mode should do for the current video. */
sealed class SmartPlan {
    /** Path A: both languages exist as embedded text tracks — merge them, no AI. */
    data class MergeEmbedded(
        val primary: SubtitleCandidate,
        val secondary: SubtitleCandidate,
        val primaryLanguage: SubtitleLanguage?,
        val secondaryLanguage: SubtitleLanguage,
        /** The secondary track only says "Chinese" (script unknown) and was accepted as the best available. */
        val ambiguousScript: Boolean,
    ) : SmartPlan()

    /** Path B: translate the primary text track with Gemini/Groq. */
    data class AiTranslate(
        val primary: SubtitleCandidate,
        val primaryLanguage: SubtitleLanguage?,
        val target: SubtitleLanguage,
        val why: BilingualNotice.Kind,
    ) : SmartPlan()

    /** Only the primary can be shown (no second language available without AI, or same language). */
    data class PrimaryOnly(val primary: SubtitleCandidate, val why: BilingualNotice.Kind) : SmartPlan()

    /** No readable text subtitle at all. */
    data class Unavailable(val why: BilingualNotice.Kind) : SmartPlan()
}

/** A short status line for the user about what the smart mode is doing and why. */
data class BilingualNotice(val kind: Kind, val detail: String = "", val id: Long = ids.incrementAndGet()) {
    enum class Kind {
        MERGING_EMBEDDED,
        MERGING_AMBIGUOUS_SCRIPT,
        AI_NO_SECONDARY_TRACK,
        AI_SECONDARY_UNRELIABLE,
        AI_SECONDARY_IMAGE_ONLY,
        PRIMARY_ONLY_NO_AI,
        PRIMARY_ONLY_UNRELIABLE_NO_AI,
        SAME_LANGUAGE,
        NO_TEXT_SUBTITLE,
        USER_PICK_RESPECTED,
        MERGE_NO_SECONDARY,
        MERGE_UNRELIABLE,
        AI_FORCED,
        AI_NOT_CONFIGURED,
    }

    /** True for notices that tell the user something is missing or degraded. */
    val isWarning: Boolean
        get() = kind != Kind.MERGING_EMBEDDED && kind != Kind.AI_NO_SECONDARY_TRACK && kind != Kind.AI_FORCED

    fun message(): String = when (kind) {
        Kind.MERGING_EMBEDDED -> "Bilingual: merging embedded $detail subtitles (no AI)"
        Kind.MERGING_AMBIGUOUS_SCRIPT -> "Bilingual: merging $detail — the track doesn't say Simplified or Traditional"
        Kind.AI_NO_SECONDARY_TRACK -> "Bilingual: no embedded $detail track — translating with AI"
        Kind.AI_SECONDARY_UNRELIABLE -> "Bilingual: embedded $detail track doesn't line up — switched to AI translation"
        Kind.AI_SECONDARY_IMAGE_ONLY -> "Bilingual: embedded $detail track is an image subtitle — translating with AI"
        Kind.PRIMARY_ONLY_NO_AI -> "Bilingual: no embedded $detail track and no AI key — showing one language. Add a Gemini/Groq key to translate."
        Kind.PRIMARY_ONLY_UNRELIABLE_NO_AI -> "Bilingual: embedded $detail track doesn't line up and no AI key is set — showing one language"
        Kind.SAME_LANGUAGE -> "Bilingual: primary and secondary are both $detail — choose a different language in Bilingual settings"
        Kind.NO_TEXT_SUBTITLE -> "Bilingual: this video has no readable text subtitle (image subtitles can't be used)"
        Kind.USER_PICK_RESPECTED -> "Bilingual: keeping the subtitle you picked"
        Kind.MERGE_NO_SECONDARY -> "Merge mode: no embedded $detail text track to merge (AI is off in this mode). Pick the tracks in Subtitles → Bilingual Subtitles."
        Kind.MERGE_UNRELIABLE -> "Merge mode: the $detail track doesn't line up with the primary (AI is off in this mode). Pick other tracks in Subtitles → Bilingual Subtitles."
        Kind.AI_FORCED -> "AI Translate mode: translating into $detail"
        Kind.AI_NOT_CONFIGURED -> "AI Translate mode needs a Gemini/Groq key (Settings → AI Subtitles) — showing one language"
    }

    private companion object {
        val ids = AtomicLong(0)
    }
}

/**
 * Pure decision of the smart bilingual mode (no Android, no I/O), in the required priority:
 * two embedded text tracks → merge (A) → otherwise AI-translate the primary (B) → otherwise say
 * clearly why nothing better is possible.
 */
object SmartBilingualPlanner {

    data class Input(
        val candidates: List<SubtitleCandidate>,
        /** "auto" or a BCP 47 tag. */
        val primaryPreference: String,
        val secondaryLanguage: SubtitleLanguage,
        val audioLanguageTag: String?,
        /** The subtitle currently selected in the player. */
        val selectedId: String?,
        /** The user explicitly picked [selectedId] from the subtitle menu: it must stay the primary. */
        val userPinnedSelection: Boolean,
        val aiConfigured: Boolean,
        /** Secondary tracks already found not to line up with this video's primary. */
        val unreliableSecondaryIds: Set<String> = emptySet(),
        /** Auto / Merge / AI Translate. */
        val mode: BilingualGenerationMode = BilingualGenerationMode.AUTO,
        /** Track the user chose as primary in the bilingual panel (wins over everything). */
        val primaryOverrideId: String? = null,
        /** Track the user chose as secondary in the bilingual panel (used whatever its language). */
        val secondaryOverrideId: String? = null,
    )

    fun plan(input: Input): SmartPlan {
        val text = input.candidates.filter { it.isText }
        val secondaryLang = input.secondaryLanguage
        val primary = resolvePrimary(input, text) ?: return SmartPlan.Unavailable(BilingualNotice.Kind.NO_TEXT_SUBTITLE)
        val primaryLang = primary.language

        if (SubtitleLanguageMatcher.sameLanguage(primaryLang, secondaryLang) &&
            SubtitleLanguageMatcher.match(primaryLang, secondaryLang) != LanguageMatch.NONE) {
            return SmartPlan.PrimaryOnly(primary, BilingualNotice.Kind.SAME_LANGUAGE)
        }

        // AI Translate mode: always translate the chosen primary, whatever tracks exist.
        if (input.mode == BilingualGenerationMode.AI_TRANSLATE) {
            if (SubtitleLanguageMatcher.sameLanguage(primaryLang, secondaryLang) &&
                SubtitleLanguageMatcher.match(primaryLang, secondaryLang) != LanguageMatch.NONE) {
                return SmartPlan.PrimaryOnly(primary, BilingualNotice.Kind.SAME_LANGUAGE)
            }
            return if (input.aiConfigured) SmartPlan.AiTranslate(primary, primaryLang, secondaryLang, BilingualNotice.Kind.AI_FORCED)
            else SmartPlan.PrimaryOnly(primary, BilingualNotice.Kind.AI_NOT_CONFIGURED)
        }

        // A secondary track picked by hand is used as long as it is readable text.
        text.firstOrNull { it.id == input.secondaryOverrideId && it.id != primary.id && it.tapTrackId != null }?.let { manual ->
            if (manual.id !in input.unreliableSecondaryIds) {
                return SmartPlan.MergeEmbedded(primary, manual, primaryLang, manual.language ?: secondaryLang, ambiguousScript = false)
            }
        }

        val pool = text.filter { it.isEmbedded && it.id != primary.id && !it.isForced }
        val exact = pool.filter { SubtitleLanguageMatcher.match(it.language, secondaryLang) == LanguageMatch.EXACT }
        val ambiguous = pool.filter { SubtitleLanguageMatcher.match(it.language, secondaryLang) == LanguageMatch.AMBIGUOUS_SCRIPT }
        val readable = { c: SubtitleCandidate -> c.tapTrackId != null && c.id !in input.unreliableSecondaryIds }
        val secondary = rank(exact.filter(readable)).firstOrNull()
            ?: rank(ambiguous.filter(readable)).firstOrNull()

        if (secondary != null) {
            return SmartPlan.MergeEmbedded(
                primary = primary,
                secondary = secondary,
                primaryLanguage = primaryLang,
                secondaryLanguage = secondaryLang,
                ambiguousScript = SubtitleLanguageMatcher.match(secondary.language, secondaryLang) == LanguageMatch.AMBIGUOUS_SCRIPT,
            )
        }

        val hadUnreliable = (exact + ambiguous).any { it.id in input.unreliableSecondaryIds }
        val imageOnly = input.candidates.any {
            it.isBitmap && it.isEmbedded && SubtitleLanguageMatcher.match(it.language, secondaryLang) != LanguageMatch.NONE
        }
        if (input.mode == BilingualGenerationMode.MERGE) {
            return SmartPlan.PrimaryOnly(
                primary,
                if (hadUnreliable || input.secondaryOverrideId in input.unreliableSecondaryIds) BilingualNotice.Kind.MERGE_UNRELIABLE
                else BilingualNotice.Kind.MERGE_NO_SECONDARY,
            )
        }
        if (input.aiConfigured) {
            val why = when {
                hadUnreliable -> BilingualNotice.Kind.AI_SECONDARY_UNRELIABLE
                imageOnly && exact.isEmpty() && ambiguous.isEmpty() -> BilingualNotice.Kind.AI_SECONDARY_IMAGE_ONLY
                else -> BilingualNotice.Kind.AI_NO_SECONDARY_TRACK
            }
            return SmartPlan.AiTranslate(primary, primaryLang, secondaryLang, why)
        }
        return SmartPlan.PrimaryOnly(
            primary,
            if (hadUnreliable) BilingualNotice.Kind.PRIMARY_ONLY_UNRELIABLE_NO_AI else BilingualNotice.Kind.PRIMARY_ONLY_NO_AI,
        )
    }

    /**
     * Primary track: the user's own pick is never replaced; an explicit Primary language picks its
     * best track; Auto uses the selected text track (when it is not the secondary language), then
     * the audio language, then English, then any non-forced text track.
     */
    internal fun resolvePrimary(input: Input, text: List<SubtitleCandidate>): SubtitleCandidate? {
        text.firstOrNull { it.id == input.primaryOverrideId }?.let { return it }
        val selected = text.firstOrNull { it.id == input.selectedId }
        if (input.userPinnedSelection && selected != null) return selected

        val notSecondary = text.filter {
            SubtitleLanguageMatcher.match(it.language, input.secondaryLanguage) == LanguageMatch.NONE
        }
        val explicit = SubtitleLanguage.parse(input.primaryPreference)
        if (explicit != null) {
            val exact = text.filter { SubtitleLanguageMatcher.match(it.language, explicit) == LanguageMatch.EXACT }
            val amb = text.filter { SubtitleLanguageMatcher.match(it.language, explicit) == LanguageMatch.AMBIGUOUS_SCRIPT }
            rank(exact.filter { !it.isForced }).firstOrNull()?.let { return it }
            rank(amb.filter { !it.isForced }).firstOrNull()?.let { return it }
            // Explicit language not available: fall through to Auto rather than show nothing.
        }
        if (selected != null && !selected.isForced && selected in notSecondary) return selected
        val audio = SubtitleLanguage.parse(input.audioLanguageTag)
        if (audio != null) {
            rank(notSecondary.filter { !it.isForced && SubtitleLanguageMatcher.match(it.language, audio) != LanguageMatch.NONE })
                .firstOrNull()?.let { return it }
        }
        rank(notSecondary.filter { !it.isForced && it.language?.base == "en" }).firstOrNull()?.let { return it }
        rank(notSecondary.filter { !it.isForced }).firstOrNull()?.let { return it }
        return rank(notSecondary).firstOrNull()
    }

    /** Embedded first, readable off-track, plain over SDH, default flag, then original order. */
    private fun rank(list: List<SubtitleCandidate>): List<SubtitleCandidate> =
        list.withIndex().sortedWith(
            compareByDescending<IndexedValue<SubtitleCandidate>> { it.value.isEmbedded }
                .thenByDescending { it.value.tapTrackId != null }
                .thenBy { it.value.looksSdh }
                .thenByDescending { it.value.isDefault }
                .thenBy { it.index }
        ).map { it.value }
}
