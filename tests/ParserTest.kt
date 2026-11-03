import recloudstream.*

var failures = 0
fun check(label: String, cond: Boolean, extra: String = "") {
    if (cond) println("  ok   $label") else { failures++; println("  FAIL $label $extra") }
}

fun main() {
    // BOM + CRLF + a bit of everything. Fake channels / example domains only.
    val playlist = "\uFEFF#EXTM3U x-tvg-url=\"http://example.com/epg.xml\"\r\n" +
        "#EXTINF:-1 tvg-id=\"news.one\" tvg-name=\"News One HD\" tvg-logo=\"http://img.example.com/news.png?a=b\" group-title=\"News, World\",News One\r\n" +
        "http://stream.example.com/live/news1.m3u8\r\n" +
        "\r\n" +
        "#EXTINF:-1 group-title=\"Sports\" tvg-logo='http://img.example.com/s1.png',Sports 1\r\n" +
        "#EXTVLCOPT:http-user-agent=MyAgent/1.0\r\n" +
        "#EXTVLCOPT:http-referrer=http://site.example.com/\r\n" +
        "http://stream.example.com/live/sports1.ts\r\n" +
        "#EXTINF:0,Movie A (2020)\n" +
        "#EXTGRP:Movies\n" +
        "http://vod.example.com/movie/user/pass/101.mp4\n" +
        "#EXTINF:-1 tvg-name=\"Only tvg-name\",\n" +
        "http://stream.example.com/x|User-Agent=Kodi%2F1.0&Referer=http%3A%2F%2Fr.example.com%2F\n" +
        "#EXTINF:-1,Json Headers\n" +
        "#EXTHTTP:{\"cookie\":\"a=b; c=d\",\"User-Agent\":\"JsonUA\"}\n" +
        "https://stream.example.com/json.m3u8\n" +
        "#EXTINF:-1,Relative Channel\n" +
        "channels/rel.m3u8\n" +
        "#EXTINF:-1 tvg-logo=\"\" group-title=\"\",No Logo No Group\n" +
        "rtmp://stream.example.com/app/stream1\n" +
        "http://no-extinf.example.com/path/Some%20Name.m3u8\n"

    println("detection")
    check("looksLikeM3u", M3uParser.looksLikeM3u(playlist))
    check("html is not m3u", !M3uParser.looksLikeM3u("<html><body>404</body></html>"))
    check("not hls manifest", !M3uParser.isHlsManifest(playlist))
    check("hls manifest", M3uParser.isHlsManifest("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8"))
    check("vod url", M3uParser.isVodUrl("http://a/b/c.MP4?token=1") && !M3uParser.isVodUrl("http://a/b/c.m3u8"))

    println("parse")
    val e = M3uParser.parse(playlist, "http://lists.example.com/dir/list.m3u")
    check("entry count == 8", e.size == 8, "got ${e.size}: ${e.map { it.name }}")

    val n1 = e[0]
    check("name from title", n1.name == "News One", n1.name)
    check("url", n1.url == "http://stream.example.com/live/news1.m3u8")
    check("group with comma kept", n1.group == "News, World", n1.group.toString())
    check("logo with = in url", n1.logo == "http://img.example.com/news.png?a=b", n1.logo.toString())
    check("tvg-id", n1.tvgId == "news.one")

    val s1 = e[1]
    check("single-quoted logo", s1.logo == "http://img.example.com/s1.png", s1.logo.toString())
    check("vlcopt user agent", s1.headers["User-Agent"] == "MyAgent/1.0", s1.headers.toString())
    check("vlcopt referrer", s1.headers["Referer"] == "http://site.example.com/", s1.headers.toString())
    check("options do not leak to next entry", e[2].headers.isEmpty(), e[2].headers.toString())

    val m = e[2]
    check("extgrp after extinf", m.group == "Movies", m.group.toString())
    check("movie name", m.name == "Movie A (2020)", m.name)

    val k = e[3]
    check("name falls back to tvg-name", k.name == "Only tvg-name", k.name)
    check("kodi url stripped", k.url == "http://stream.example.com/x", k.url)
    check("kodi UA percent-decoded", k.headers["User-Agent"] == "Kodi/1.0", k.headers.toString())
    check("kodi referer percent-decoded", k.headers["Referer"] == "http://r.example.com/", k.headers.toString())

    val j = e[4]
    check("exthttp cookie", j.headers["Cookie"] == "a=b; c=d", j.headers.toString())
    check("exthttp UA canonical", j.headers["User-Agent"] == "JsonUA", j.headers.toString())

    val r = e[5]
    check("relative url resolved", r.url == "http://lists.example.com/dir/channels/rel.m3u8", r.url)

    val ng = e[6]
    check("empty logo -> null", ng.logo == null)
    check("empty group -> null", ng.group == null)
    check("rtmp kept", ng.url == "rtmp://stream.example.com/app/stream1")

    val bare = e[7]
    check("no EXTINF: name from url", bare.name == "Some Name.m3u8", bare.name)

    println("sources")
    val src = M3uParser.parseSources(
        "# comment\n" +
        "https://iptv.example.com/a.m3u\n" +
        "My List | https://iptv.example.com/b.m3u8\n" +
        "https://other.example.org/c.m3u\n" +
        "https://iptv.example.com/a.m3u\n" +          // duplicate
        "garbage line\n" +
        "/sdcard/lists/local.m3u\n" +
        "https://iptv.example.com/d.m3u|User-Agent=VLC\n"   // pipe stays part of the URL
    )
    println("   -> " + src)
    check("5 sources", src.size == 5, src.size.toString())
    check("default name is host", src[0].name == "iptv.example.com", src[0].name)
    check("explicit name", src[1].name == "My List" && src[1].source == "https://iptv.example.com/b.m3u8")
    check("local file name", src.any { it.name == "local.m3u" })
    check("duplicate names made unique", src.map { it.name }.toSet().size == src.size, src.map { it.name }.toString())
    check("pipe kept in url", src.last().source.endsWith("|User-Agent=VLC"))
    check("pipe suffix ignored in default name", src.last().name == "iptv.example.com (2)", src.last().name)


    println("series")
    fun ep(t: String) = M3uParser.parseEpisodeTitle(t)
    ep("Breaking Bad S01E01").let {
        check("S01E01", it != null && it.series == "Breaking Bad" && it.season == 1 && it.episode == 1 && it.title == null, it.toString())
    }
    ep("Breaking Bad - S02 E10 - Pilot").let {
        check("separators + episode title", it != null && it.series == "Breaking Bad" && it.season == 2 && it.episode == 10 && it.title == "Pilot", it.toString())
    }
    ep("Show Name 1x05").let { check("1x05", it != null && it.series == "Show Name" && it.season == 1 && it.episode == 5, it.toString()) }
    ep("Mi Serie Temporada 3 Episodio 7").let { check("temporada/episodio", it != null && it.series == "Mi Serie" && it.season == 3 && it.episode == 7, it.toString()) }
    ep("Mi Serie TEMP. 2 CAPÍTULO 12").let { check("temp./capítulo uppercase", it != null && it.series == "Mi Serie" && it.season == 2 && it.episode == 12, it.toString()) }
    ep("Season 4 Episode 9 of Show").let { check("no series name before marker -> null", it == null, it.toString()) }
    ep("The Show Season 4 Episode 9").let { check("season/episode words", it != null && it.series == "The Show" && it.season == 4 && it.episode == 9, it.toString()) }
    ep("Mi Serie T02E03 [1080p]").let { check("quality tag is not a title", it != null && it.season == 2 && it.episode == 3 && it.title == null, it.toString()) }
    ep("Dr. Who (2005) S01E01").let { check("year kept in series name", it != null && it.series == "Dr. Who (2005)", it.toString()) }
    ep("show.name.s03e04.720p").let { check("dotted release name", it != null && it.series == "show.name" && it.season == 3 && it.episode == 4, it.toString()) }
    check("movie with number is not an episode", ep("Blade Runner 2049") == null)
    check("resolution is not an episode", ep("Some Movie 1920x1080") == null)
    check("S01E01 without a show name -> null", ep("S01E01 Only Episode") == null)
    check("plain channel name", ep("Sports 1 HD") == null)

    fun entry(name: String, url: String, group: String? = null) = M3uEntry(name = name, url = url, group = group)
    check("mkv episode detected", M3uParser.episodeInfo(entry("Show S01E02", "http://h/x/y/1.mkv")) != null)
    check("/series/ url detected even as m3u8", M3uParser.episodeInfo(entry("Show S01E02", "http://h/series/u/p/1.m3u8")) != null)
    check("group 'SERIES | Drama' detected", M3uParser.episodeInfo(entry("Show S01E02", "http://h/u/p/1.m3u8", "SERIES | Drama")) != null)
    check("live .ts channel named like an episode is NOT a series", M3uParser.episodeInfo(entry("Show S01E02", "http://h/live/u/p/1.ts", "Entertainment")) == null)
    check("series without S/E in title stays a normal entry", M3uParser.episodeInfo(entry("Some Documentary", "http://h/series/u/p/1.mkv")) == null)
    check("seriesKey ignores case and spacing", M3uParser.seriesKey("Breaking   Bad") == M3uParser.seriesKey(" breaking bad "))

    println("edge cases")
    check("empty text", M3uParser.parse("").isEmpty())
    check("only header", M3uParser.parse("#EXTM3U\n").isEmpty())
    check("EXTINF without url ignored", M3uParser.parse("#EXTM3U\n#EXTINF:-1,Lonely\n").isEmpty())
    check("no comma in EXTINF", M3uParser.parse("#EXTINF:-1\nhttp://a.example.com/s").first().name == "s")

    // Performance sanity check: 100k entries
    val sb = StringBuilder("#EXTM3U\n")
    for (i in 1..100_000) sb.append("#EXTINF:-1 tvg-id=\"c$i\" tvg-logo=\"http://i.example.com/$i.png\" group-title=\"G${i % 150}\",Channel $i\nhttp://s.example.com/live/$i.ts\n")
    val t0 = System.nanoTime()
    val big = M3uParser.parse(sb.toString())
    val ms = (System.nanoTime() - t0) / 1_000_000
    check("100k entries parsed", big.size == 100_000)
    println("   100k entries (${sb.length / 1_000_000} MB) parsed in $ms ms")

    println(if (failures == 0) "\nALL TESTS PASSED" else "\n$failures TEST(S) FAILED")
    if (failures != 0) System.exit(1)
}
