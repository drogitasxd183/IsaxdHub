import recloudstream.*
import java.io.StringReader

var failures = 0
fun check(label: String, cond: Boolean, extra: String = "") {
    if (cond) println("  ok   $label") else { failures++; println("  FAIL $label $extra") }
}

fun stream(json: String, section: XtreamSection): List<XtreamItem> {
    val out = ArrayList<XtreamItem>()
    XtreamJson.forEachTopLevel(StringReader(json), XtreamParser.LIST_KEYS) { key, fields ->
        if (key == null || key != "user_info") XtreamParser.item(section, fields)?.let { out.add(it) }
    }
    return out
}

fun main() {
    println("accounts")
    val a = XtreamAccounts.parse(
        "# comment\n" +
        "My provider | http://h.example:8080 | user | pass\n" +
        "http://h2.example | user2 | pa|ss\n" +
        "http://h3.example:8000/get.php?username=u3&password=p%40ss&type=m3u_plus&output=ts\n" +
        "h4.example:8080/player_api.php?username=u4&password=p4\n" +
        "http://u5:p5@h5.example:8080\n" +
        "Named | http://h6.example/get.php?username=u6&password=p6\n" +
        "http://h7.example user7 pass7\n" +
        "garbage line without anything useful\n" +
        "http://h.example:8080 | user | other-password\n" +                    // same server+user: duplicate, ignored
        "Four | http://h8.example/c/ | u8 | p|8\n"
    )
    a.forEach { println("   -> ${it.name} | ${it.baseUrl} | ${it.username} | ${it.password}") }
    check("8 accounts (garbage + duplicate dropped)", a.size == 8, a.size.toString())
    check("explicit name", a[0].name == "My provider" && a[0].baseUrl == "http://h.example:8080")
    check("url | user | pass-with-pipe: first part is the server", a[1].name == "h2.example" && a[1].baseUrl == "http://h2.example" && a[1].username == "user2" && a[1].password == "pa|ss", a[1].toString())
    check("get.php link", a[2].baseUrl == "http://h3.example:8000" && a[2].username == "u3" && a[2].password == "p@ss", a[2].toString())
    check("host without scheme + player_api.php", a[3].baseUrl == "http://h4.example:8080" && a[3].username == "u4")
    check("user:pass@host", a[4].baseUrl == "http://h5.example:8080" && a[4].username == "u5" && a[4].password == "p5", a[4].toString())
    check("name + link", a[5].name == "Named" && a[5].baseUrl == "http://h6.example" && a[5].username == "u6")
    check("space separated", a[6].baseUrl == "http://h7.example" && a[6].username == "user7" && a[6].password == "pass7")
    check("4-part form: password may contain |, /c/ path cut", a[7].baseUrl == "http://h8.example" && a[7].password == "p|8", a[7].toString())
    check("default name is the host", a[2].name == "h3.example")
    check("unique names", a.map { it.name }.toSet().size == a.size)
    check("no password in key", !a[0].key.contains("pass") && a[0].key == "http://h.example:8080|user")

    println("url builders")
    val acc = XtreamAccount("t", "http://h.example:8080", "us er", "p@ss/w+rd")
    check("api url encodes credentials", acc.apiUrl() == "http://h.example:8080/player_api.php?username=us+er&password=p%40ss%2Fw%2Brd", acc.apiUrl())
    check("api url with action and params", acc.apiUrl("get_vod_info", "vod_id" to "12") .endsWith("&action=get_vod_info&vod_id=12"))
    check("live url", acc.liveUrl("101", "m3u8") == "http://h.example:8080/live/us%20er/p%40ss%2Fw%2Brd/101.m3u8", acc.liveUrl("101", "m3u8"))
    check("movie url", acc.movieUrl("5001", "mkv").startsWith("http://h.example:8080/movie/") && acc.movieUrl("5001", "mkv").endsWith("/5001.mkv"))
    check("series url", acc.seriesUrl("9001", "mp4").contains("/series/") && acc.seriesUrl("9001", "mp4").endsWith("/9001.mp4"))

    println("category filter")
    check("empty filter accepts all", CategoryFilter("").accepts("Anything"))
    val f = CategoryFilter("latino, ES, -adultos")
    check("include word", f.accepts("LATINO | Cine") && f.accepts("ES - Noticias"))
    check("accent/case insensitive", CategoryFilter("accion").accepts("ACCI\u00d3N"))
    check("not included -> rejected", !f.accepts("USA | News"))
    check("exclude wins", !f.accepts("LATINO | Adultos"))
    check("only excludes keep everything else", CategoryFilter("-xxx").accepts("Drama") && !CategoryFilter("-xxx").accepts("XXX Zone"))

    println("json tree")
    val t = XtreamJson.parseTree("""{"a":1,"b":"x\u00f1\/y","c":[1,2.5,true,null,{"d":-3e2}],"e":null,"f":{}}""") as Map<*, *>
    check("numbers stay raw text", t["a"] == "1" && (t["c"] as List<*>)[1] == "2.5" && ((t["c"] as List<*>)[4] as Map<*, *>)["d"] == "-3e2")
    check("string escapes", t["b"] == "x\u00f1/y")
    check("true/null", (t["c"] as List<*>)[2] == "true" && (t["c"] as List<*>)[3] == null && t["e"] == null)
    check("html rejected", runCatching { XtreamJson.parseTree("<html><body>Forbidden</body></html>") }.isFailure)
    check("truncated rejected", runCatching { XtreamJson.parseTree("{\"a\":[1,2") }.isFailure)
    check("garbage after value rejected", runCatching { XtreamJson.parseTree("{} x") }.isFailure)
    check("deep nesting rejected, no crash", runCatching { XtreamJson.parseTree("[".repeat(5000)) }.isFailure)

    println("live / vod / series lists (streaming)")
    val live = stream("""[{"num":1,"name":"Canal Uno HD","stream_type":"live","stream_id":101,"stream_icon":"http:\/\/img.example.com\/1.png","epg_channel_id":"uno.es","added":"1600000000","category_id":"12","custom_sid":"","tv_archive":0,"direct_source":"","nested":{"a":[1,{"b":"}"}]}},
      {"num":2,"name":"Canal \"Dos\"","stream_id":"102","stream_icon":"","category_id":12}, {"name":"no id"}, {"stream_id":5}]""", XtreamSection.LIVE)
    check("2 live channels (entries without id or name skipped)", live.size == 2, live.size.toString())
    check("live fields", live[0].id == "101" && live[0].name == "Canal Uno HD" && live[0].icon == "http://img.example.com/1.png" && live[0].categoryId == "12", live[0].toString())
    check("numeric category id and empty icon", live[1].categoryId == "12" && live[1].icon == null && live[1].name == "Canal \"Dos\"")

    val vod = stream("""[{"num":1,"name":"Pel\u00edcula A (2020)","stream_type":"movie","stream_id":5001,"stream_icon":"http:\/\/x\/p.jpg","rating":"7.5","rating_5based":3.75,"added":"1700000000","category_id":"3","container_extension":"mkv","custom_sid":"","direct_source":""}]""", XtreamSection.VOD)
    check("vod fields", vod.size == 1 && vod[0].id == "5001" && vod[0].extension == "mkv" && vod[0].rating == "7.5" && vod[0].added == 1700000000L, vod.toString())
    check("year taken from the title", vod[0].year == 2020 && vod[0].name == "Pel\u00edcula A (2020)")

    val series = stream("""[{"num":1,"name":"Serie X","series_id":77,"cover":"http:\/\/x\/c.jpg","plot":"P","cast":"A, B","genre":"Drama","releaseDate":"2019-05-01","last_modified":"1700000001","rating":"8.2","backdrop_path":["http:\/\/x\/b.jpg"],"youtube_trailer":"abcdefghijk","episode_run_time":"45","category_id":"9"}]""", XtreamSection.SERIES)
    check("series fields use series_id and cover", series.size == 1 && series[0].id == "77" && series[0].icon == "http://x/c.jpg" && series[0].year == 2019 && series[0].added == 1700000001L, series.toString())

    println("top level variants")
    check("empty array", stream("[]", XtreamSection.LIVE).isEmpty())
    check("null and false mean nothing to list", stream("null", XtreamSection.LIVE).isEmpty() && stream("false", XtreamSection.VOD).isEmpty())
    check("object of objects", stream("""{"0":{"stream_id":1,"name":"A"},"1":{"stream_id":2,"name":"B"}}""", XtreamSection.LIVE).map { it.name } == listOf("A", "B"))
    check("whitespace and BOM", stream("\uFEFF \n [ \n {\"stream_id\" : 1 , \"name\" : \"A\"} , {\"stream_id\":2,\"name\":\"B\"} \n ]", XtreamSection.LIVE).size == 2)
    check("html is rejected", runCatching { stream("<html>Forbidden</html>", XtreamSection.LIVE) }.isFailure)
    check("empty body is rejected", runCatching { stream("", XtreamSection.LIVE) }.isFailure)
    check("truncated list is rejected", runCatching { stream("""[{"stream_id":1,"name":"A"},{"stream_id":2,"na""", XtreamSection.LIVE) }.isFailure)
    var rejectedLogin: Map<String, String>? = null
    XtreamJson.forEachTopLevel(StringReader("""{"user_info":{"auth":0,"status":"Disabled"}}"""), XtreamParser.LIST_KEYS) { k, f -> if (k == "user_info") rejectedLogin = f }
    check("rejected login is visible while streaming", rejectedLogin?.get("auth") == "0" && rejectedLogin?.get("status") == "Disabled", rejectedLogin.toString())

    println("categories / user info")
    val cats = XtreamParser.categories(XtreamJson.parseTree("""[{"category_id":"12","category_name":"ESPA\u00d1A | Deportes","parent_id":0},{"category_id":3,"category_name":"Acci\u00f3n"},{"category_id":"4"}]"""))
    check("categories", cats.size == 2 && cats[0] == XtreamCategory("12", "ESPA\u00d1A | Deportes") && cats[1].id == "3", cats.toString())
    val ui = XtreamParser.userInfo(XtreamJson.parseTree("""{"user_info":{"username":"u","message":"","auth":1,"status":"Active","exp_date":"1798675200","active_cons":"0","max_connections":"2","allowed_output_formats":["m3u8","TS"]},"server_info":{"url":"h"}}"""))
    check("user info", ui.authenticated && ui.status == "Active" && ui.expiry == 1798675200L && ui.maxConnections == "2" && ui.formats == listOf("m3u8", "ts") && !ui.isBlocked, ui.toString())
    check("auth 0 -> not authenticated", !XtreamParser.userInfo(XtreamJson.parseTree("""{"user_info":{"auth":0}}""")).authenticated)
    check("string auth '1'", XtreamParser.userInfo(XtreamJson.parseTree("""{"user_info":{"auth":"1","status":"Active"}}""")).authenticated)
    check("no user_info -> not authenticated", !XtreamParser.userInfo(XtreamJson.parseTree("[]")).authenticated)
    check("expired is blocked", XtreamParser.userInfo(XtreamJson.parseTree("""{"user_info":{"auth":1,"status":"Expired"}}""")).isBlocked)
    check("never expires -> expiry null", XtreamParser.userInfo(XtreamJson.parseTree("""{"user_info":{"auth":1,"status":"Active","exp_date":null}}""")).expiry == null)

    println("movie details")
    val vd = XtreamParser.vodDetails(XtreamJson.parseTree("""{"info":{"tmdb_id":"603","name":"Pel\u00edcula A","cover_big":"http://x/big.jpg","movie_image":"http://x/p.jpg","releasedate":"2020-03-20","youtube_trailer":"zzzzzzzzzzz","director":"Dir","actors":"X, Y","cast":"","description":"Desc","plot":"","genre":"Action, Sci-Fi","backdrop_path":["http://x/b.jpg"],"duration_secs":5700,"duration":"01:35:00","rating":"7.5"},"movie_data":{"stream_id":5001,"name":"Pel\u00edcula A","container_extension":"mkv"}}"""))
    check("vod basics", vd.name == "Pel\u00edcula A" && vd.poster == "http://x/p.jpg" && vd.backdrop == "http://x/b.jpg" && vd.plot == "Desc" && vd.year == 2020)
    check("vod lists", vd.genres == listOf("Action", "Sci-Fi") && vd.actors == listOf("X", "Y") && vd.director == "Dir")
    check("vod duration/trailer/tmdb/ext", vd.durationMinutes == 95 && vd.trailer == "https://www.youtube.com/watch?v=zzzzzzzzzzz" && vd.tmdbId == "603" && vd.extension == "mkv" && vd.rating == "7.5")
    val empty = XtreamParser.vodDetails(XtreamJson.parseTree("""{"info":[],"movie_data":[]}"""))
    check("empty info does not crash", empty.name == null && empty.genres.isEmpty() && empty.durationMinutes == null)

    println("series details")
    val sd = XtreamParser.seriesDetails(XtreamJson.parseTree("""{"seasons":[],"info":{"name":"Serie X","cover":"http://x/c.jpg","plot":"Plot","cast":"A, B","director":"D","genre":"Drama / Thriller","releaseDate":"2019-05-01","rating":"8.2","backdrop_path":"http://x/b.jpg","youtube_trailer":"https://youtu.be/abc","episode_run_time":"45","tmdb":"1234"},
        "episodes":{"2":[{"id":"9003","episode_num":"1","title":"S02E01","container_extension":"mkv","info":{"duration":"00:44:10"},"season":2}],
                    "1":[{"id":"9002","episode_num":2,"title":"","container_extension":"mp4","info":{}},{"id":"9001","episode_num":1,"title":"Serie X - S01E01 - Piloto","container_extension":"mkv","info":{"movie_image":"http://x/e1.jpg","plot":"Ep plot","duration_secs":2700},"season":1}]}}"""))
    check("series info", sd.details.name == "Serie X" && sd.details.genres == listOf("Drama", "Thriller") && sd.details.backdrop == "http://x/b.jpg" && sd.details.durationMinutes == 45 && sd.details.tmdbId == "1234" && sd.details.trailer == "https://youtu.be/abc")
    check("3 episodes sorted by season then number", sd.episodes.map { it.id } == listOf("9001", "9002", "9003"), sd.episodes.map { it.id }.toString())
    check("season from key when the episode has none", sd.episodes[1].season == 1 && sd.episodes[1].number == 2 && sd.episodes[1].title == null)
    check("episode info", sd.episodes[0].poster == "http://x/e1.jpg" && sd.episodes[0].plot == "Ep plot" && sd.episodes[0].durationMinutes == 45 && sd.episodes[0].extension == "mkv")
    check("clock duration of an episode", sd.episodes[2].durationMinutes == 44)
    val sd2 = XtreamParser.seriesDetails(XtreamJson.parseTree("""{"info":{"name":"S"},"episodes":[[{"id":"1","episode_num":1,"container_extension":"mkv"}],[{"id":"2","episode_num":1,"container_extension":"mkv"}]]}"""))
    check("list of lists -> index is the season", sd2.episodes.map { it.season } == listOf(1, 2), sd2.episodes.map { it.season }.toString())
    check("flat list of episodes", XtreamParser.seriesDetails(XtreamJson.parseTree("""{"info":{},"episodes":[{"id":"1","episode_num":3,"season":2}]}""")).episodes.single().let { it.season == 2 && it.number == 3 })
    check("no episodes", XtreamParser.seriesDetails(XtreamJson.parseTree("""{"info":{"name":"S"},"episodes":[]}""")).episodes.isEmpty())

    println("helpers")
    check("yearFrom", XtreamParser.yearFrom("Movie (1999) HD") == 1999 && XtreamParser.yearFrom("2160p 12345") == null && XtreamParser.yearFrom(null) == null && XtreamParser.yearFrom("2020-03-20") == 2020)
    check("durationMinutes", XtreamParser.durationMinutes("5700", null, null) == 95 && XtreamParser.durationMinutes(null, "02:00:00", null) == 120 && XtreamParser.durationMinutes(null, null, "90") == 90 && XtreamParser.durationMinutes("0", "", "") == null)
    check("trailerUrl", XtreamParser.trailerUrl("abcdefghijk") == "https://www.youtube.com/watch?v=abcdefghijk" && XtreamParser.trailerUrl("https://x/y") == "https://x/y" && XtreamParser.trailerUrl("nope") == null && XtreamParser.trailerUrl("") == null)
    check("ratingValue", XtreamParser.ratingValue("7,5") == 7.5 && XtreamParser.ratingValue("0") == null && XtreamParser.ratingValue("85") == null && XtreamParser.ratingValue("abc") == null)



    println("account forms (settings screen)")
    val nasty = XtreamAccountForm(server = "http://h.example:8080", username = "us\"er\\1", password = "p|a'ss \u00f1 \uD83D\uDD11\nx", name = "Mi lista")
    val stored = XtreamAccountForms.encode(listOf(nasty, XtreamAccountForm("h2.example", "u2", "p2")))
    check("stored form is a JSON array", stored.startsWith("[{\"server\":") && stored.endsWith("}]"), stored)
    check("round trip with quotes, backslash, pipe, unicode, emoji, newline", XtreamAccountForms.decode(stored) == listOf(nasty, XtreamAccountForm("h2.example", "u2", "p2")), stored)
    check("empty list round trips", XtreamAccountForms.decode(XtreamAccountForms.encode(emptyList())).isEmpty())
    check("nothing stored -> no forms", XtreamAccountForms.decode("").isEmpty() && XtreamAccountForms.decode("   ").isEmpty())

    val legacy = XtreamAccountForms.decode("My provider | http://h.example:8080 | user | pass\nhttp://h3.example:8000/get.php?username=u3&password=p3&type=m3u_plus\n")
    check("old one-per-line text is migrated", legacy.size == 2, legacy.toString())
    check("migrated form keeps server, user, password and the name", legacy[0] == XtreamAccountForm("http://h.example:8080", "user", "pass", "My provider"), legacy[0].toString())
    check("migrated link gives clean server and credentials", legacy[1].server == "http://h3.example:8000" && legacy[1].username == "u3" && legacy[1].password == "p3", legacy[1].toString())
    check("old line starting with [ is not mistaken for JSON", XtreamAccountForms.decode("[ES] Prov | http://h.example | u | p").single().let { it.name == "[ES] Prov" && it.username == "u" })
    check("migration keeps the account identity (favourites survive)", XtreamAccountForms.convert(legacy).accounts.map { it.key } == XtreamAccounts.parse("My provider | http://h.example:8080 | user | pass\nhttp://h3.example:8000/get.php?username=u3&password=p3").map { it.key })
    check("garbage JSON entries are skipped", XtreamAccountForms.decode("""[{"server":"h","user":"u","pass":"p"}, 5, "x", {"server":"h2"}]""").size == 2)

    fun conv(vararg f: XtreamAccountForm) = XtreamAccountForms.convert(f.toList())
    val c1 = conv(XtreamAccountForm("  http://h.example:8080/  ", "  user ", " pass  "))
    check("spaces around the boxes are ignored, trailing slash removed", c1.accounts.single().let { it.baseUrl == "http://h.example:8080" && it.username == "user" && it.password == "pass" } && c1.ignored == 0, c1.accounts.toString())
    check("server without scheme gets http://", conv(XtreamAccountForm("h.example:8080", "u", "p")).accounts.single().baseUrl == "http://h.example:8080")
    check("https is kept", conv(XtreamAccountForm("https://secure.example", "u", "p")).accounts.single().baseUrl == "https://secure.example")
    check("blank forms are skipped without being counted as errors", conv(XtreamAccountForm(), XtreamAccountForm("  ", "", "")).let { it.accounts.isEmpty() && it.ignored == 0 })
    val inc = conv(XtreamAccountForm("http://h.example", "u", ""), XtreamAccountForm("", "u", "p"), XtreamAccountForm("http://h.example", "", "p"), XtreamAccountForm("http://ok.example", "u", "p"))
    check("incomplete forms are counted, the complete one is kept", inc.ignored == 3 && inc.accounts.single().baseUrl == "http://ok.example", inc.accounts.toString() + " ignored=" + inc.ignored)
    check("server with spaces is invalid", conv(XtreamAccountForm("not a server", "u", "p")).let { it.accounts.isEmpty() && it.ignored == 1 })

    val link = conv(XtreamAccountForm("http://h.example:8000/get.php?username=lu&password=lp%40ss&type=m3u_plus&output=ts", "", ""))
    check("M3U link in the server box fills user and password", link.accounts.single().let { it.baseUrl == "http://h.example:8000" && it.username == "lu" && it.password == "lp@ss" }, link.accounts.toString())
    val mixed = conv(XtreamAccountForm("http://h.example:8000/get.php?username=lu&password=lp", "mine", ""))
    check("typed boxes win over the link, empty ones are completed from it", mixed.accounts.single().let { it.username == "mine" && it.password == "lp" }, mixed.accounts.toString())
    val userinfo = conv(XtreamAccountForm("http://uu:pp@h.example:8080", "", ""))
    check("user:password@host in the server box", userinfo.accounts.single().let { it.baseUrl == "http://h.example:8080" && it.username == "uu" && it.password == "pp" }, userinfo.accounts.toString())
    check("server box with player_api.php path", conv(XtreamAccountForm("http://h.example:8080/player_api.php", "u", "p")).accounts.single().baseUrl == "http://h.example:8080")

    val dups = conv(XtreamAccountForm("http://h.example:8080", "u", "p1"), XtreamAccountForm("http://h.example:8080/", "u", "p2"), XtreamAccountForm("http://h.example:8080", "other", "p"))
    check("same server and user twice is kept once", dups.accounts.size == 2 && dups.accounts[0].password == "p1", dups.accounts.toString())
    check("same host, different users: names tell them apart", dups.accounts.map { it.name } == listOf("h.example (u)", "h.example (other)"), dups.accounts.map { it.name }.toString())
    val names = conv(XtreamAccountForm("http://a.example", "u", "p"), XtreamAccountForm("http://b.example", "u", "p"), XtreamAccountForm("http://b.example:81", "u", "p"))
    check("different hosts keep their host as name; same host on another port gets a number", names.accounts.map { it.name } == listOf("a.example", "b.example (u)", "b.example (u) (2)"), names.accounts.map { it.name }.toString())
    check("a name kept from the old version is used", conv(XtreamAccountForm("http://h.example", "u", "p", "Casa")).accounts.single().name == "Casa")
    check("isBlank", XtreamAccountForm().isBlank && XtreamAccountForm(" ", " ", " ").isBlank && !XtreamAccountForm(password = "x").isBlank)

    println("home rows planning")
    fun item(id: Int, cat: String?, name: String = "Item $id") = XtreamItem(id.toString(), name, null, cat, null, null, 0L, null)
    val cs = listOf(XtreamCategory("1", "Action"), XtreamCategory("2", "Drama"), XtreamCategory("3", "Empty"), XtreamCategory("4", "LATINO | Cine"))
    val its = listOf(item(1, "1"), item(2, "2"), item(3, "1"), item(4, null), item(5, "99"), item(6, "4"))
    val plan = XtreamRows.plan(cs, its, CategoryFilter(""), "Other", 10, 10)
    check("rows follow the panel's category order, empty categories skipped", plan.rows.map { it.categoryName } == listOf("Action", "Drama", "LATINO | Cine", "Other"), plan.rows.map { it.categoryName }.toString())
    check("items go to their category", plan.rows[0].items.map { it.id } == listOf("1", "3") && plan.rows[1].items.map { it.id } == listOf("2"))
    check("missing and unknown categories go to the last row", plan.rows.last().items.map { it.id } == listOf("4", "5"), plan.rows.last().items.map { it.id }.toString())
    check("matching counts every category that passes the filter", plan.matching == 4)
    val filtered = XtreamRows.plan(cs, its, CategoryFilter("latino, action"), "Other", 10, 10)
    check("include filter", filtered.rows.map { it.categoryName } == listOf("Action", "LATINO | Cine") && filtered.matching == 2)
    check("exclude filter", XtreamRows.plan(cs, its, CategoryFilter("-drama, -other"), "Other", 10, 10).rows.map { it.categoryName } == listOf("Action", "LATINO | Cine"))
    check("filter matching nothing -> no rows", XtreamRows.plan(cs, its, CategoryFilter("nothing"), "Other", 10, 10).let { it.rows.isEmpty() && it.matching == 0 })
    val capped = XtreamRows.plan(cs, its, CategoryFilter(""), "Other", 2, 1)
    check("row limit keeps the first rows but reports all matches", capped.rows.map { it.categoryName } == listOf("Action", "Drama") && capped.matching == 4)
    check("item limit per row", capped.rows[0].items.size == 1 && capped.rows[0].items[0].id == "1")
    check("no categories at all -> everything in one row", XtreamRows.plan(emptyList(), its, CategoryFilter(""), "Other", 10, 10).rows.single().items.size == 6)
    check("no items -> no rows", XtreamRows.plan(cs, emptyList(), CategoryFilter(""), "Other", 10, 10).rows.isEmpty())
    val bigItems = (1..50_000).map { item(it, (it % 200).toString()) }
    val bigCats = (0 until 200).map { XtreamCategory(it.toString(), "Cat $it") }
    val t1 = System.nanoTime()
    val bigPlan = XtreamRows.plan(bigCats, bigItems, CategoryFilter(""), "Other", 60, 120)
    check("50k items / 200 categories planned", bigPlan.rows.size == 60 && bigPlan.matching == 200 && bigPlan.rows.all { it.items.size == 120 })
    println("   planned in ${(System.nanoTime() - t1) / 1_000_000} ms")

    println("live format")
    check("m3u8 when allowed", chooseLiveExtension(false, listOf("m3u8", "ts")) == "m3u8")
    check("ts when m3u8 is not allowed", chooseLiveExtension(false, listOf("ts", "rtmp")) == "ts")
    check("ts when the panel's formats are unknown", chooseLiveExtension(false, emptyList()) == "ts")
    check("ts when preferred", chooseLiveExtension(true, listOf("m3u8", "ts")) == "ts")

    println("XtreamRef")
    val ref = XtreamRef(kind = REF_VOD, name = "Pel\u00edcula \"A\" \\ \uD83C\uDFAC", acc = "http://h.example:8080|user", id = "5001", logo = "http://x/p.jpg", ext = "mkv", cat = "Acci\u00f3n, Aventura")
    val json = ref.encode()
    check("starts with {\"name\": (fixUrl-safe)", json.startsWith("{\"name\":"), json)
    check("round trip", XtreamRef.decode(json) == ref, json)
    check("no password in the reference", !json.contains("pass"))
    check("info ref round trip", XtreamRef(kind = REF_INFO, name = "t", info = "line1\nline2").let { XtreamRef.decode(it.encode()) == it })
    check("garbage -> null", XtreamRef.decode("nope") == null && XtreamRef.decode(null) == null && XtreamRef.decode("{\"name\":\"x\"}") == null && XtreamRef.decode("[1]") == null)

    println("performance (200k entries with extra fields and nesting)")
    val sb = StringBuilder("[")
    for (i in 1..200_000) {
        if (i > 1) sb.append(',')
        sb.append("{\"num\":$i,\"name\":\"Movie $i (20${10 + i % 15})\",\"stream_type\":\"movie\",\"stream_id\":$i,\"stream_icon\":\"http:\\/\\/img.example.com\\/p\\/$i.jpg\",\"rating\":\"7.$i\",\"rating_5based\":3.5,\"added\":\"1700000000\",\"category_id\":\"${i % 150}\",\"container_extension\":\"mkv\",\"custom_sid\":\"\",\"direct_source\":\"\",\"info\":{\"a\":[1,2,{\"b\":\"x\"}]}}")
    }
    sb.append("]")
    val big = sb.toString()
    val t0 = System.nanoTime()
    val items = stream(big, XtreamSection.VOD)
    val ms = (System.nanoTime() - t0) / 1_000_000
    check("200k entries streamed", items.size == 200_000 && items.last().id == "200000" && items[10].categoryId == "11")
    println("   200k entries (${big.length / 1_000_000} MB) streamed in $ms ms")

    println(if (failures == 0) "\nALL TESTS PASSED" else "\n$failures TEST(S) FAILED")
    if (failures != 0) System.exit(1)
}
