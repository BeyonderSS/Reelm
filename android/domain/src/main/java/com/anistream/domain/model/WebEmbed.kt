package com.anistream.domain.model

/**
 * Last-resort "web player": the public VidSrc-family embed page, opened in an in-app WebView.
 * Keyed by IMDb id, so anime (no IMDb id) never gets here.
 */
object WebEmbed {
    /** Primary embed host. Checked 2026-10-20: vsembed.ru and vidsrc.sh answer 200 (same backend);
     *  vidsrc.xyz / vidsrc.to don't resolve here and vidsrc.cc returns 403. */
    const val HOST = "vsembed.ru"
    val MIRRORS = listOf(HOST, "vidsrc.sh")

    /** Movie when season/episode are 0, else an episode page. [imdbId] may be "tmdb:<movie|tv>:<id>" for a TMDB-only title. */
    fun url(host: String, imdbId: String, season: Int, episode: Int): String {
        val key = if (imdbId.startsWith("tmdb:")) "tmdb=${imdbId.substringAfterLast(':')}" else "imdb=$imdbId"
        return if (season > 0 && episode > 0) "https://$host/embed/tv?$key&season=$season&episode=$episode"
        else "https://$host/embed/movie?$key"
    }

    /**
     * Navigation filter. Top-level navigations may only stay on a mirror (ads hijack window.top / window.open).
     * Sub-frames are allowed on any web host: the embed loads its player iframe from a rotating
     * domain (e.g. stellarconductornexus.com via /vs_src.php), which can't be allow-listed.
     * Non-web schemes (intent://, market://, …) are always blocked.
     */
    fun allows(scheme: String?, host: String?, mainFrame: Boolean): Boolean {
        val s = scheme?.lowercase()
        val web = s == "https" || s == "http"
        if (!mainFrame) return web || s == "about" || s == "data" || s == "blob"
        val h = host?.lowercase() ?: return false
        return web && MIRRORS.any { h == it || h.endsWith(".$it") }
    }
}
