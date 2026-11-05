<p align="center"><img src="icon.png" width="112" alt="M3U Player"> &nbsp; <img src="icon-xtream.png" width="112" alt="Xtream Player"></p>
<p align="center"><a href="README.md">Español</a> · <b>English</b></p>

# M3U Player and Xtream Player — CloudStream plugins

Two plugins for [CloudStream](https://github.com/recloudstream/cloudstream) in one repository:

| Plugin | What it is for |
|---|---|
| **M3U Player** | Plays **M3U / M3U8 (IPTV) playlists** from the URLs you configure. |
| **Xtream Player** | Connects to an **Xtream Codes** account (server, username and password) and separates channels, movies and series. |

Neither includes channels or sources: you bring your own playlist or account (use them only with content you have the right to watch).

## M3U Player — features

- One or several M3U playlists (`http(s)` URL or local file), each with its own name.
- The home screen shows **one row per group** (`group-title`) with each channel's logo (`tvg-logo`). Search by channel or group name (up to 200 results).
- Plays HLS (`.m3u8`), DASH (`.mpd`), MPEG-TS, MP4/MKV and extension-less IPTV URLs.
- **Three kinds of content**:
  - **Live TV**: any regular entry.
  - **Movies**: `.mp4`, `.mkv`, `.avi`, `.mov`, `.wmv`, `.webm` and `.m4v` files.
  - **TV series**: grouped into a single page with seasons and episodes (see below).
- Per-channel HTTP headers: `#EXTVLCOPT:http-user-agent` / `http-referrer`, `#EXTHTTP:{...}`, `#KODIPROP:inputstream.adaptive.stream_headers` and the Kodi-style suffix `url|User-Agent=VLC&Referer=https://site`.
- Reads `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `#EXTGRP`, relative paths, BOM and CRLF line endings. Playlists are downloaded without the 5 MB limit and the parser handles 100,000 entries in about 1 second.
- If the URL you configure is itself an HLS stream (not a channel list), it is played as it is.
- The plugin's own screens and messages are available in Spanish and English, following the phone's language.

### How series are detected

An entry is treated as an **episode** when both conditions are met:

1. Its title contains a season and an episode: `Show S01E02`, `Show S1 E2`, `Show S01.E02`, `Show T01E02`, `Show 1x02`, `Show Temporada 1 Episodio 2`, `Show Season 1 Episode 2`, `Show Temp. 1 Cap. 2`.
2. It is a video file (`.mkv`, `.mp4`...), **or** its URL contains `/series/`, **or** it sits in a group whose name contains "serie".

The series name is the text **before** the season/episode marker (`Breaking Bad - S02 E10 - Pilot` → series "Breaking Bad", season 2, episode 10, title "Pilot"). All episodes with the same name inside the same playlist form one page, sorted by season and episode.
If the titles in your playlist have no season and episode, those entries will show up as movies.

## M3U Player — setup inside CloudStream

1. Open the **M3U Player** provider on the home screen.
2. Tap the **gear icon** next to the provider name (or *Settings → Extensions → M3U Player → Settings*).
3. Type one playlist per line and save:

```
https://server.example/list.m3u
My list | https://server.example/other.m3u8
https://server.example/list.m3u|User-Agent=VLC
/sdcard/Download/list.m3u
# lines starting with # are ignored
```

4. Reopen the provider to reload the channels. Playlists are cached in memory for 10 minutes.

> On Android TV, CloudStream does not show the gear icon on the home screen; use the *Settings → Extensions* route instead.

## Xtream Player

Connects to an **Xtream Codes** account (the `player_api.php` format used by many IPTV panels). The panel serves live channels, movies and series separately, so the plugin **knows exactly what each entry is**: it never guesses from names or URLs. It needs no API key (not even a TMDb one): posters, plots and the rest of the data come from the panel itself.

### Features

- **Live TV, Movies and Series** as separate sections (enable only the ones you want), with **one row per category** of the panel.
- Inside each category, movies and series are listed **newest first**; channels keep the panel's order.
- **Full detail pages** for movies and series with whatever the panel provides: poster, background image, plot, year, genres, cast, director, duration, rating, trailer and TMDb ID.
- Series with **seasons and episodes** (title, image, plot and duration of each episode).
- **Search** across the channels, movies and series of all your accounts; it ignores case and accents.
- Several accounts at once. If one fails, the others keep working.
- Live channels over **HLS (m3u8)** when the panel allows it, or **TS**; you can force TS in the settings.
- Huge catalogues: the lists are read as a stream and only what is needed is kept (in the tests, 200,000 entries in about 2 seconds on a PC). They stay in memory for 15 minutes.
- The plugin's own texts appear in Spanish or English depending on the phone's language.

### Setup

1. Open the **Xtream Player** provider on the home screen and tap the **gear icon** (or *Settings → Extensions → Xtream Player → Settings*).
2. Type one account per line, in any of these forms:

```
My provider | http://server:8080 | username | password
http://server:8080 | username | password
http://server:8080/get.php?username=USERNAME&password=PASSWORD&type=m3u_plus
# lines starting with # are ignored
```

   The third form is the M3U link providers usually hand out: the plugin extracts the server, username and password from it (it does not download that M3U list; it uses the Xtream API).
3. Optional settings:
   - **Sections**: tick only the ones you want to see. Every enabled section downloads its catalogue when the home screen opens.
   - **Category filter**: words separated by commas; only categories whose name contains one of them are shown (case and accents are ignored), and a leading `-` excludes. Example: `LATINO, ES, -ADULT`. Use it if your panel has hundreds of categories: the home screen shows at most 60 rows per section and 120 items per row (find the rest with the search).
   - **User-Agent**: some providers only answer certain clients (for example `VLC/3.0.20 LibVLC/3.0.20`).
   - **Use TS instead of HLS**: for live channels.
4. Tap **Test connection** to check each account before saving: it shows the status, the expiry date, the connections in use and the allowed output formats.
5. Tap **Save** and reopen the provider.

How each thing is played: channels with `server/live/username/password/ID.m3u8` (or `.ts`), movies with `server/movie/username/password/ID.extension` and episodes with `server/series/username/password/ID.extension`. Favourites and history store a reference to the account (server and username), not the password.

---

# Build and install, step by step

There are two ways. **Option A needs neither Android Studio nor the Android SDK**: GitHub builds the plugin for you. It is the recommended one.

## Option A — Build with GitHub (recommended)

You need: a GitHub account, Git installed and about 15 minutes.

### 1. Install Git

- Windows: download the installer from <https://git-scm.com/download/win> and accept the default options.
- macOS: run `xcode-select --install` in Terminal.
- Linux: `sudo apt install git` (or your distribution's package manager).

Check that it works by opening a terminal (on Windows: *PowerShell* or *Git Bash*) and running:

```
git --version
```

### 2. Create the repository on GitHub

1. Go to <https://github.com> and sign in (or create a free account).
2. Click **+ → New repository** (top right).
3. *Repository name*: `cloudstream-m3u` (any name works).
4. Select **Public** (CloudStream must be able to read the files without signing in).
5. Do **not** tick *Add a README*, *.gitignore* or *license*. Click **Create repository**.
6. Note the address: `https://github.com/YOUR_USER/cloudstream-m3u`.

### 3. Unzip the project

Unzip the project archive. Go into the folder that directly contains the `build.gradle.kts` file (if unzipping created a folder inside another one, it is the innermost one).

### 4. Edit `repo.json` and `iconUrl`

1. Open `repo.json` with a text editor and replace **`YOUR_USER/YOUR_REPO`** with your user and repository. Example:

   ```json
   "https://raw.githubusercontent.com/YOUR_USER/cloudstream-m3u/builds/plugins.json"
   ```

2. Open `M3uPlayer/build.gradle.kts` and `XtreamPlayer/build.gradle.kts` and do the same in the `iconUrl` line of each (see *Changing the icon* below). Save all three files.

### 5. Upload the project to GitHub

Open a terminal **inside that folder** (Windows 11: right-click an empty spot in the folder → *Open in Terminal*; Windows 10: Shift + right-click → *Open PowerShell window here*). Run these commands **in this order**, changing your name, your email, `YOUR_USER` and the repository name:

```
git config --global user.name "Your Name"
git config --global user.email "your-email@example.com"

git init -b main
git add .
git commit -m "M3U Player plugin"
git remote add origin https://github.com/YOUR_USER/cloudstream-m3u.git
git push -u origin main
```

- The *LF will be replaced by CRLF* warnings on Windows are normal.
- The first time you run `git push`, Git asks you to sign in to GitHub: a browser window opens, accept. If it asks for a username and password instead, use a *Personal Access Token* (GitHub → Settings → Developer settings → Personal access tokens) with the `repo` and `workflow` permissions.
- That upload **triggers the automatic build**. The workflow creates the `builds` branch (where the compiled plugin is published) by itself if it does not exist yet.

### 6. Wait for GitHub to build

1. In your GitHub repository open the **Actions** tab.
2. You will see a run named **Build**. Wait for the **green ✔** (3 to 8 minutes).
3. Open the `builds` branch (branch drop-down): it must contain `M3uPlayer.cs3` and `plugins.json`.

If you get a red ❌, open the run, click the step that failed and copy the error message (see *Troubleshooting* below).

### 7. Add the repository in CloudStream

1. On your phone open CloudStream → **Settings → Extensions**.
2. Tap **Add repository** (the **+** button).
3. *Name*: `M3U Player`. *URL*:

```
https://raw.githubusercontent.com/YOUR_USER/cloudstream-m3u/main/repo.json
```

4. Open the repository you just added and download **M3U Player**, **Xtream Player** or both (each one has its own download button). Accept the warning about third-party repositories.

### 8. Set up your playlists or your account

Go back to the home screen, pick the provider (**M3U Player** or **Xtream Player**), tap the gear icon and fill in the settings (see *M3U Player — setup* and *Xtream Player*).

### Updating the plugin later

1. Change whatever you want in the code.
2. Raise the `version` number in the `build.gradle.kts` of the plugin you changed (`M3uPlayer/build.gradle.kts` or `XtreamPlayer/build.gradle.kts`; each plugin has its own version). **Without this change CloudStream does not detect the update.**
3. In the terminal: `git add .`, `git commit -m "changes"`, `git push`. (On the GitHub website you can also edit a file with the pencil button and commit.)
4. Wait for the green ✔ in *Actions* and update the plugin from *Settings → Extensions*.

### Changing the icon

CloudStream loads the plugin icon from a **public image URL** (`iconUrl` in each plugin's `build.gradle.kts`). The easiest way is to keep the image in this repository:

1. Replace `icon.png` (M3U Player) or `icon-xtream.png` (Xtream Player) with your own square PNG (512×512 recommended). Keep the file name, or update `iconUrl` if you change it.
2. Raise `version` by one and commit, as in *Updating the plugin later*.
3. Update the plugin in CloudStream. The icon only changes after the update, and GitHub may take a few minutes to refresh the image.

If you prefer an image hosted elsewhere, put its **direct** link (one that ends in `.png` / `.jpg` and opens the image itself, not a web page) in `iconUrl`.

## Option B — Build on your PC

You need JDK 17 and the Android SDK. The first build downloads hundreds of MB and takes 5-15 minutes.

### 1. Install JDK 17

Download **Temurin 17** from <https://adoptium.net> and install it, ticking *Set JAVA_HOME* and *Add to PATH*. Check with `java -version` (it must say 17).

### 2. Install the Android SDK

1. Install **Android Studio** from <https://developer.android.com/studio>.
2. Open it once and complete the initial setup wizard.
3. Go to **More Actions → SDK Manager → SDK Platforms**, tick **Android 15.0 (API 35)** and click *Apply*.
4. In the *SDK Tools* tab make sure *Android SDK Build-Tools* and *Platform-Tools* are installed.
5. At the top of that window you will see **Android SDK Location**; copy it (on Windows it is usually `C:\Users\YOUR_USER\AppData\Local\Android\Sdk`).

### 3. Tell Gradle where the SDK is

In the project folder (where `build.gradle.kts` is) create a file named `local.properties` with one line (use forward slashes `/` even on Windows):

```
sdk.dir=C:/Users/YOUR_USER/AppData/Local/Android/Sdk
```

### 4. Build

Open a terminal in the project folder.

- Windows (PowerShell): `.\gradlew.bat M3uPlayer:make`
- macOS / Linux: `./gradlew M3uPlayer:make`

When it finishes you will see `Made CloudStream package at ...` and the file will be at:

```
M3uPlayer/build/M3uPlayer.cs3
```

### 5. Install the file on your phone

1. Copy `M3uPlayer.cs3` to the `Cloudstream3/plugins/` folder of the phone's internal storage (create it if it does not exist).
2. Fully close CloudStream and open it again.

Depending on your Android version, CloudStream may need file-access permission to read that folder. If the plugin does not show up, use Option A.

---

## Troubleshooting

| Problem | Solution |
|---|---|
| The workflow fails at *Create the builds branch* or *Checkout builds* | Create the `builds` branch by hand with these commands (in the project folder), then in *Actions* click **Re-run all jobs**: `git checkout --orphan builds`, `git rm -rf .`, `git commit --allow-empty -m init`, `git push -u origin builds`, `git checkout main`. |
| The workflow fails at *Push builds* with a 403 error | *Settings → Actions → General → Workflow permissions* → **Read and write permissions** → *Save*, then run it again. |
| `git push` rejects the workflow file | The token needs the `workflow` permission (see step 5). |
| CloudStream says the repository URL is not valid | The repository must be **public** and the URL must be the `raw.githubusercontent.com/.../main/repo.json` one. Open it in a browser: it must show the JSON. |
| The repository loads but is empty | Open `https://raw.githubusercontent.com/YOUR_USER/cloudstream-m3u/builds/plugins.json` in a browser. If it returns 404, the workflow did not finish correctly. |
| The workflow fails with `Could not find com.github.recloudstream...` | JitPack builds those libraries on demand and the first request can fail. The workflow already retries 4 times by itself (2 minutes apart); if it still fails, click **Re-run all jobs** in *Actions* after a few minutes. |
| Xtream: "Wrong username or password" | Check the server, port, username and password with **Test connection**. Copy the data exactly as your provider gives it (the port matters). |
| Xtream: "The server did not return valid Xtream data" | The address or port are not those of the Xtream API, or the provider blocks the client. Try another **User-Agent** (for example `VLC/3.0.20 LibVLC/3.0.20`). |
| Xtream: a section is empty or has few categories | Check the **Category filter** and that the account includes that section. |
| Xtream: the detail page loads but it does not play | Check the connections in use and the expiry date with **Test connection** (some providers allow a single simultaneous connection). For channels, try the TS option, or turn it off. |
| Local: `SDK location not found` | Create `local.properties` (step 3 of option B) or set the `ANDROID_HOME` variable. |
| Local: `Could not resolve com.github.recloudstream...` | JitPack builds those libraries on demand; wait a few minutes and run the command again. |
| Local: Java version error | Use JDK 17 (`java -version`). |
| The new icon does not appear | Raise `version`, push, wait for the green ✔ and update the plugin in CloudStream (or remove it and install it again). |

## Project layout

| File | What it contains |
|---|---|
| `M3uPlayer/src/main/kotlin/recloudstream/M3uParser.kt` | M3U parser and series detection in pure Kotlin (no Android or CloudStream) |
| `M3uPlayer/src/main/kotlin/recloudstream/ItemRef.kt` | Each channel's/series' data stored as JSON, with a small reflection-free codec |
| `M3uPlayer/src/main/kotlin/recloudstream/M3uProvider.kt` | The provider: group-based home page, search, detail pages, series and playback |
| `M3uPlayer/src/main/kotlin/recloudstream/M3uSettings.kt` | Settings dialog and playlist storage |
| `M3uPlayer/src/main/kotlin/recloudstream/M3uPlugin.kt` | Plugin entry point |
| `M3uPlayer/build.gradle.kts` | M3U Player version, description, types and icon |
| `XtreamPlayer/src/main/kotlin/recloudstream/XtreamJson.kt` | Own JSON readers: streaming (huge lists) and tree (small responses) |
| `XtreamPlayer/src/main/kotlin/recloudstream/XtreamCore.kt` | Accounts, category filter, models, Xtream API parsing and references (pure Kotlin) |
| `XtreamPlayer/src/main/kotlin/recloudstream/XtreamClient.kt` | Xtream API requests and in-memory cache |
| `XtreamPlayer/src/main/kotlin/recloudstream/XtreamProvider.kt` | The provider: sections, rows per category, search, detail pages, series and playback |
| `XtreamPlayer/src/main/kotlin/recloudstream/XtreamSettings.kt` | Settings dialog, "Test connection" and storage |
| `XtreamPlayer/src/main/kotlin/recloudstream/XtreamPlugin.kt` | Xtream Player entry point |
| `XtreamPlayer/build.gradle.kts` | Xtream Player version, description, types and icon |
| `icon.png` / `icon-xtream.png` | Plugin icons (512×512), referenced by `iconUrl` |
| `.github/workflows/build.yml` | Automatic build on GitHub Actions |
| `tests/ParserTest.kt` | M3U parser and JSON codec tests (run on any JVM) |
| `tests/XtreamTest.kt` | Xtream Player core tests (run on any JVM) |

Test the parsers:

```
kotlinc M3uPlayer/src/main/kotlin/recloudstream/M3uParser.kt M3uPlayer/src/main/kotlin/recloudstream/ItemRef.kt tests/ParserTest.kt -include-runtime -d parser-test.jar
java -jar parser-test.jar

kotlinc XtreamPlayer/src/main/kotlin/recloudstream/XtreamJson.kt XtreamPlayer/src/main/kotlin/recloudstream/XtreamCore.kt tests/XtreamTest.kt -include-runtime -d xtream-test.jar
java -jar xtream-test.jar
```

## Known limitations

**M3U Player**

- No DRM (`#KODIPROP:...license_key`), EPG or catch-up/timeshift support.
- Series are grouped by title only (no external posters or synopses are fetched); they use the logo from the playlist.
- Channels whose URL has no extension are played as a plain byte stream (it works with MPEG-TS, the usual case); if a server delivers HLS without `.m3u8` in the URL, add that extension or ask for the `output=m3u8` link.
- Changing the playlists does not affect a row that is already loaded until you reopen the provider.

**Xtream Player**

- No EPG (programme guide), catch-up/timeshift or recordings.
- The data on each detail page is what the panel provides (TMDb is not queried); if the panel has little data, the page will be simple.
- Favourites and history depend on the account still being configured with the same server and username.
- Every enabled section downloads its whole catalogue when the home screen opens (it is kept in memory for 15 minutes).
- If the provider limits simultaneous connections, playback may be cut while another session is open with the same account.
