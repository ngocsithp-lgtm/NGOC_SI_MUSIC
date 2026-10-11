# NGỌC SĨ AI — Firebase AI Logic preview

**Status: experimental, unmerged, and not deployed.** This isolated branch adds the Firebase AI Logic Android SDK to the chat preview. It does not modify `main`, replace the installed production app, publish a release, or run an APK build as part of this change.

## What the preview uses

- Free-form chat is sent directly from the preview app through the Firebase AI Logic SDK using the Gemini Developer API backend and model `gemini-3.8-flash`.
- App Check with Play Integrity is installed before the AI Logic client is created. Firebase AI Logic setup must be completed and App Check enforcement configured for the registered preview app.
- No Gemini API key is embedded in the Android app. Firebase AI Logic uses the Firebase client configuration. Do not add the Gemini Developer API to the Firebase API key's allowed API list.
- If Firebase client settings are missing or point to a different project, the preview remains offline rather than sending a request.
- The recent chat transcript stays in the preview app's private local preferences (up to 60 visible messages). The app does not sync it to cloud storage; the **XÓA** action clears it. Online requests include only the most recent validated conversation history. Never enter passwords, verification codes, bank details, or other sensitive information.
- Playback and status commands are checked locally using an allowlist through this preview's own Media3 session: play, pause, next, previous, current track, shuffle, repeat, and sleep timer/navigation commands. The preview's queue is separate from the installed production app's queue. Text that does not match a local command is sent to Firebase AI Logic only when the user submits it and online configuration is present.
- The microphone action is user-initiated. Android speech recognition transcribes the phrase before the app follows the same local-command / chat submission path. The experimental **wake phrase** is separate: the user must start its foreground service while the app is visible. It requires microphone permission and a visible ongoing notification; it uses on-device recognition only and stops if the Vietnamese offline model is unavailable. This is a best-effort prototype, not a dedicated low-power wake-word model.
- The older Cloud Functions source remains in this repository as a separate backend experiment, but this branch's Android chat client does **not** call `ngocSiAiChat`. The function is not required for Firebase AI Logic and is not deployed by this branch.

## Firebase setup

Use the existing Firebase project with ID `ngoc-si-music-ai` if it is available in your account. Do not create a duplicate project. The production app is `com.ngocsi.music`; this preview is deliberately registered separately as `com.ngocsi.music.aipreview`.

1. Open https://console.firebase.google.com/ and select the project. In **Project settings**, verify the ID is exactly `ngoc-si-music-ai`. Stop if it differs.
2. Open **AI Services → AI Logic → Get started**. Choose **Gemini Developer API** and complete the guided setup. This is the direct Firebase AI Logic route; a Gemini API key or Cloud Functions deployment is not required by this Android path.
3. In **Project settings → Your apps**, add a second Android app with the package name exactly `com.ngocsi.music.aipreview` if it does not already exist. Keep the production app registration unchanged.
4. Open **Security → App Check**. Register the preview Android app with **Play Integrity** and add the SHA-256 reported by a stable signed preview build when one is intentionally built. Since this preview may be sideloaded outside Google Play, review the distribution settings for apps installed outside Play Store; do not blindly require Play Store recognition for sideloaded testing.
5. In **Security → App Check → APIs**, confirm Firebase AI Logic is enforced. Do not disable App Check to work around a rejected request.

Official guides: https://firebase.google.com/docs/ai-logic/get-started?platform=android and https://firebase.google.com/docs/app-check/android/play-integrity-provider.

## GitHub Actions client settings

This branch reads Firebase client settings from build-time environment variables and deliberately falls back to offline mode when they are absent. It does not automatically read `google-services.json`; uploading that file to a different branch does not populate these settings.

On your phone, open the repository on GitHub → **Settings → Secrets and variables → Actions**. Configure these repository secrets only after confirming that the values belong to the same project. Do not paste them into this chat.

| Secret | Source |
| --- | --- |
| `FIREBASE_API_KEY` | `api_key[0].current_key` from Firebase config |
| `FIREBASE_PROJECT_ID` | `project_info.project_id`; must equal `ngoc-si-music-ai` |
| `FIREBASE_SENDER_ID` | `project_info.project_number` |
| `FIREBASE_DEBUG_APP_ID` | `mobilesdk_app_id` belonging to the `com.ngocsi.music.aipreview` Firebase app |

The app ID for the preview is separate from the production `com.ngocsi.music` app ID. Firebase configuration values are client configuration, not a Gemini model key, but the Firebase API key should be restricted to the APIs the app actually uses. Never add a Gemini API key to Android, the APK, or GitHub Android build secrets.

## Costs, limits, and privacy

- Firebase AI Logic with the Gemini Developer API provides a no-cost usage tier subject to current model availability, quotas, and Google's data-use terms. **No-cost use is not unlimited and is not a promise of zero charges in every configuration.** Review the current model quota/pricing and terms before enabling online requests.
- This direct AI Logic path does not require deploying the Cloud Function or turning on Blaze solely for that function. Other Firebase products and configurations may have separate billing requirements.
- Provider quotas are enforced by Google's service. This preview branch does not apply the custom 10-per-user / 50-global daily counters from the separate Cloud Function experiment.
- Do not send sensitive information. Read the current usage/data terms in the Firebase AI Logic documentation.

## Validation boundary

This change was committed only to `feature/ngoc-si-ai-firebase-ai-logic-preview`. It has **not** been compiled or tested by a workflow yet, and no APK has been built for this change. Firebase console setup and an explicit on-device online test remain necessary. A passing code change by itself is not evidence that the live Firebase endpoint is working.
