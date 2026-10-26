# EgyDead CloudStream Extension

Custom repository and provider extension for streaming Arabic and international movies, TV series, and anime from **EgyDead** (`tv10.egydead.live`) directly in the [CloudStream](https://github.com/recloudstream/cloudstream) application.

## Features

- **Search**: Fast Arabic and English query support.
- **Home Browsing**: Categorized feeds for Featured (`/h3/`), Movies, TV Series, Anime, and Recent Episodes.
- **Details**: Synopsis, release year, IMDb rating, tags, and ordered season/episode indexing.
- **Video Extraction**: Automatic resolution of multi-server embeds (Streamtape, FileMoon, DoodStream, etc.) and direct MP4/M3U8 streams.

## One-Click CloudStream Installation

1. Open **CloudStream** on your Android / Android TV device.
2. Go to **Settings** (⚙️) ➔ **Extensions** ➔ **Add Repository**.
3. Fill in the following:
   - **Repository Name**: `EgyDead Repo`
   - **Repository URL**:
     ```text
     https://raw.githubusercontent.com/romus2250/EgyDeadExtension/main/repo.json
     ```
4. Click **Download / Add**.
5. Switch to the **Arabic** / **EgyDead** tab, tap **EgyDead**, and select **Install**.

## Building Locally

### Prerequisites
- JDK 17 or higher
- Android SDK Build-Tools 35

### Build Command
```bash
chmod +x gradlew
./gradlew make makePluginsJson
```

Compiled `.cs3` files will be placed inside `EgyDeadProvider/build/`.
