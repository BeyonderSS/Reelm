package com.anistream.domain

/**
 * Per-profile "show mature & non-film content" switch. [active] = the filter is ON (default; also when nobody is signed
 * in) and is set by the account layer from the active profile's `mature`. Applies to MovieBox genres and TMDB rows/picks.
 */
object MaturePolicy {
    @Volatile var active = true

    private val ADULT = setOf("erotic", "hot", "adult", "18+")
    private val FILM = setOf(
        "action", "adventure", "animation", "anime", "biography", "comedy", "crime", "documentary", "drama", "family", "fantasy",
        "history", "horror", "music", "musical", "mystery", "romance", "sci-fi", "science fiction", "sport", "thriller", "war",
        "western", "reality", "reality-tv", "talk-show", "news", "kids", "short"
    )

    private val ADULT_TITLE = Regex("\\b(rape|porn|porno|xxx|nude|erotic|hentai|orgy|gang ?bang)\\b", RegexOption.IGNORE_CASE)
    // ponytail: known Indian erotic web series (ALTBalaji/Ullu) that TMDB lists as Crime/Drama, adult=false, with tame
    // synopses, so neither the genre nor the adult flag catches them. A name list misses the next one; TMDB keywords
    // ("erotic", 1 request per title) would catch it if this list keeps growing.
    private val ADULT_SERIES = Regex("\\b(gand+i+ baat|charmsukh|palang ?tod|kavita bhabhi|mastram|rasbhari|x\\.?x\\.?x\\b.*uncensored)", RegexOption.IGNORE_CASE)

    /** Adult genre, or genres that are all non-film (sports clips, music videos, "Modern Drama"). No genres = kept. */
    fun hides(genres: List<String>, title: String = ""): Boolean {
        if (ADULT_TITLE.containsMatchIn(title) || ADULT_SERIES.containsMatchIn(title)) return true
        val g = genres.map { it.trim().lowercase() }
        return g.isNotEmpty() && (g.any { it in ADULT } || g.none { it in FILM })
    }
}
