# NGỌC SĨ AI — UI preview only

This branch adds a first-pass, local-only chat screen for the Android app.

## What is included

- A home quick-access tile labelled **NGỌC SĨ AI**.
- A dedicated dark-theme chat screen with user/assistant message bubbles, suggested prompts, text input, send action, and optional Android speech recognition for dictation.
- A clear banner that says this is a preview and that online AI is not connected.
- Chat messages remain in the screen's in-memory state. The preview does not send them to Firebase, Gemini, or another network service.

## What is intentionally not included

- No Firebase SDK changes.
- No Gemini API key, cloud secret, API request, or billing setup.
- No automatic music control from AI.
- No change to the launcher activity, package ID, version code, signing, existing playback, Drive, YouTube, Radio, or TV behavior.
- This branch does not publish or replace the current APK.

## Validation

The dedicated workflow runs Android lint, unit tests, and a debug APK build. It does not publish an APK. The screen must be tested on a device before this UI is considered complete. Actual AI replies require a separately reviewed server connection and end-to-end testing.
