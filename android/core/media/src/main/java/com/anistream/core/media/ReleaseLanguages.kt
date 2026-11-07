package com.anistream.core.media

/** Audio languages a release/post/file name advertises ("Dual Audio [Hindi ORG + English]" → hi, en), ISO 639-1, in order. */
object ReleaseLanguages {
    private val FULL = mapOf(
        "hindi" to "hi", "english" to "en", "tamil" to "ta", "telugu" to "te", "malayalam" to "ml", "kannada" to "kn",
        "bengali" to "bn", "bangla" to "bn", "marathi" to "mr", "punjabi" to "pa", "gujarati" to "gu", "urdu" to "ur",
        "japanese" to "ja", "korean" to "ko", "chinese" to "zh", "mandarin" to "zh", "cantonese" to "zh", "spanish" to "es",
        "latino" to "es", "french" to "fr", "german" to "de", "italian" to "it", "portuguese" to "pt", "russian" to "ru",
        "arabic" to "ar", "turkish" to "tr", "thai" to "th", "indonesian" to "id"
    )
    // short forms collide with title words ("Ben-Hur", "Tel Aviv"), so they only count next to an audio marker
    private val SHORT = mapOf(
        "hin" to "hi", "eng" to "en", "tam" to "ta", "tel" to "te", "mal" to "ml", "kan" to "kn", "ben" to "bn",
        "jap" to "ja", "jpn" to "ja", "kor" to "ko", "spa" to "es", "fre" to "fr", "ger" to "de", "ita" to "it", "rus" to "ru"
    )
    private val MARKER = Regex("""(?i)\b(dual|multi|audio|dub(bed)?|org|dd\+?\s?\d|ddp|aac|ac3)\b""")
    private val DUAL = Regex("""(?i)\b(dual|multi)[\s._-]*audio\b""")
    private val WORD = Regex("""[a-z]+""")

    /** [title] (the movie's own name) is ignored, so "English Vinglish" or "Hindi Medium" don't count as languages. */
    fun parse(name: String, title: String? = null): List<String> {
        val text = (if (title.isNullOrBlank()) name else name.replace(title, " ", ignoreCase = true)).lowercase()
        val marked = MARKER.containsMatchIn(text)
        val found = WORD.findAll(text).mapNotNull { FULL[it.value] ?: SHORT[it.value]?.takeIf { marked } }.distinct().toMutableList()
        // ponytail: Indian sites' bare "Dual Audio" is Hindi + English almost always; trust a named pair when given
        if (DUAL.containsMatchIn(text)) {
            if (found.isEmpty()) found += "hi"
            if (found.size == 1 && found[0] != "en") found += "en"
        }
        return found
    }
}
