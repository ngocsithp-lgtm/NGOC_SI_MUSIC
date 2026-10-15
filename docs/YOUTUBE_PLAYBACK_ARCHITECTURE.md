# YouTube playback architecture for NGỌC SĨ MUSIC

## Goals
- Keep YouTube search and video selection inside NGỌC SĨ MUSIC.
- Keep the YouTube embedded player visible and compliant with YouTube playback requirements.
- Use the existing Media3 `MusicService` for audio sources that provide a direct, authorized audio stream and permit background playback.
- Preserve the stable 5.38 PRO build on `main`; this work stays on a separate preview branch.

## Playback boundaries
1. YouTube search results may be displayed with title, channel, and thumbnail metadata.
2. A selected YouTube video plays in the official embedded YouTube player while the player is visible.
3. Do not extract or isolate the audio track from YouTube videos, scrape undocumented stream URLs, block ads, or attempt to keep the embedded YouTube player running as an invisible background player.
4. Background playback, lock-screen controls, queue handling, and screen-off playback use Media3 only for sources whose access terms explicitly permit that behavior (for example, local files, user-authorized Drive media, and approved streaming providers).
5. If the user wants screen-off playback for a YouTube video, show a clear explanation that this requires YouTube's supported playback experience/entitlement rather than a hidden third-party audio path.

## Implementation sequence
- [ ] Review current YouTube search and player lifecycle for crashes, retry behavior, orientation changes, and queue restoration.
- [ ] Verify Media3 foreground service, audio focus, notification, and lock-screen controls for permitted audio sources.
- [ ] Add automated checks for URL/video ID sanitization and queue parsing where feasible.
- [ ] Build and test a preview APK without changing `main` or replacing the user's installed 5.38 PRO APK.

## Acceptance checks
- YouTube search and visible embedded playback work in the app.
- Video errors (including unavailable/embedding-disabled videos) show a retry action and an optional external-open action.
- Local and authorized Drive audio continue in the background with lock-screen controls.
- No YouTube audio extraction, undocumented endpoint, or background-player policy bypass is introduced.
