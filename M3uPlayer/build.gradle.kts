// Use an integer for version numbers
version = 1

cloudstream {
    description = "Reproduce listas M3U / M3U8 (IPTV) a partir de tus propias URLs | Plays M3U / M3U8 (IPTV) playlists from your own URLs"
    authors = listOf("you")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Live", "Movie", "TvSeries")

    // Uses android.* classes (settings dialog), so it cannot be a cross-platform plugin.
    isCrossPlatform = false
}
