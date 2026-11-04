// Use an integer for version numbers
version = 2

dependencies {
    // The Android-side classes (Plugin, Context helpers...) are not part of the "library" artifact.
    // The CloudStream Gradle plugin downloads them (release classes.jar) as a compile-only dependency.
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}

cloudstream {
    description = "Reproduce listas M3U / M3U8 (IPTV) a partir de tus propias URLs | Plays M3U / M3U8 (IPTV) playlists from your own URLs"
    authors = listOf("drogitasxd183")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Live", "Movie", "TvSeries")

    // Shown in CloudStream's extensions list. It must be a public image URL: the file lives in this repository.
    iconUrl = "https://raw.githubusercontent.com/drogitasxd183/cloudstream-m3u/main/icon.png"

    // Uses android.* classes (settings dialog), so it cannot be a cross-platform plugin.
    isCrossPlatform = false
}
