# THE WIRED MUSIC - Android

Native Kotlin / Jetpack Compose port of the Windows player. Same look (ice-blue glass panels, liquid visualiser),
YouTube + Spotify, lyrics over the visualiser, downloads.

## Get the APK
1. Create a **new GitHub repo** and upload the contents of this folder (the `.github` folder must be at the repo root).
2. Open the **Actions** tab, wait for "Build THE WIRED MUSIC (Android)" to finish (~5-8 min the first time).
3. Open the finished run, download the **THE-WIRED-MUSIC-apk** artifact, unzip, and install `app-release.apk` on your phone
   (allow "install unknown apps" when asked). Needs Android 10 or newer.

If the build fails, copy the red error text from the Actions log and send it back.
If it fails on resolving `NewPipeExtractor`, open `app/build.gradle.kts` and change `v0.24.8` to the latest tag from
github.com/TeamNewPipe/NewPipeExtractor/releases. Do the same whenever YouTube stops working: YouTube changes often and
NewPipeExtractor ships fixes.

## How it works
- **Playback** runs in a foreground service (Media3/ExoPlayer): the next song always starts with the screen off or the app
  minimized, and you get lock-screen / notification / Bluetooth controls.
- **YouTube**: search, video links and playlists through NewPipeExtractor (no Python, no ffmpeg, so the app stays small).
- **Spotify**: song / album / playlist links need no login or keys. Songs are matched to the same song on YouTube (YouTube
  Music first, closest length wins) and play in full. Spotify search needs free developer keys (menu > Spotify keys).
- **Lyrics**: tap "Search lyrics" on the visualiser. Nothing is looked up until you tap. Source: LRCLIB (free, no login), synced
  lyrics scroll with the song. Found lyrics are remembered for next time.
- **Downloads**: saved as M4A (original quality, no re-encoding) in Music/TheWiredMusic, with title/artist/album set.
- **Share**: share a Spotify or YouTube link to the app from any other app to add it.
- Volume uses the phone's volume buttons.
