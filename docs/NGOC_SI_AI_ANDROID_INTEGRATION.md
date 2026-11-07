# NGỌC SĨ AI — Firebase AI Logic preview

**Status: experimental, isolated, unmerged, and not deployed.** The current `ngocsiAI` Preview branch adds Firebase AI Logic, local voice control, an allowlisted app-action tool, and a separate Preview package. It does not modify `main`, replace the installed production app, publish a release, or deploy backend services.

## What the preview uses

- Free-form chat is sent directly from the preview app through the Firebase AI Logic SDK using the Gemini Developer API backend. Greetings and very lightweight chat use `gemini-3.5-flash-lite` with `MINIMAL` thinking; substantive factual questions, complex reasoning, and app-tool requests use `gemini-3.8-flash`. Factual questions keep `LOW` thinking and a 768-token answer budget so the stronger model does not automatically add the long reasoning delay; complex requests use the policy-selected `MEDIUM`/`HIGH` level and larger bounded budgets. This is intent-based primary-model routing, not a quota fallback. No automatic switch to a paid model is performed.
- Debug Preview builds install the Firebase App Check Debug provider and require the exact debug token to be registered for the `com.ngocsi.music.aipreview` app. Non-debug variants use Play Integrity. Keep enforcement enabled and never substitute a debug token for release attestation.
- No Gemini API key is embedded in the Android app. Firebase AI Logic uses the Firebase client configuration. Do not add the Gemini Developer API to the Firebase API key's allowed API list.
- If Firebase client settings are missing or point to a different project, the preview remains offline rather than sending a request.
- The recent chat transcript stays in the preview app's private local preferences (up to 60 visible messages). The app does not sync it to cloud storage; the **XÓA** action clears it. Online requests include only the most recent validated conversation history. Never enter passwords, verification codes, bank details, or other sensitive information.
- Playback and status commands use a shared allowlisted router for typed chat, Gemini tool calls, and the hands-free voice service. The shared Media3 executor handles queue playback, pause, next/previous, seek, playback speed, current-track status, shuffle, and repeat consistently. The Preview can bind the stable app's MediaSession or use its own Preview session according to the active playback target. Navigation, Android volume, STOP target selection, and YouTube WebView controls remain host-specific because they require different Android services and lifecycle state. Text that does not match a local command is sent to Firebase AI Logic only when the user submits it and online configuration is present.
- When NGỌC SĨ AI is opened from a feature page, the app passes an allowlisted page label (YouTube, Google Drive, Radio, TV, Library, Playlist, and supported tabs) as internal system context. The label is not added to the visible transcript or saved conversation. The same context is forwarded to hands-free Gemini turns while the wake-word service is active. Local allowlisted playback commands continue to run on-device without waiting for Gemini.
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

The Preview branch has passed GitHub Actions Android lint, unit tests, debug APK packaging, native-library alignment, package isolation, and APK-signature checks on its last validated build. It remains unmerged and has not been deployed. Firebase console setup and explicit on-device tests are still required to verify live online responses, App Check, speech recognition, and background playback. A passing CI build is not evidence that every live device workflow works.
