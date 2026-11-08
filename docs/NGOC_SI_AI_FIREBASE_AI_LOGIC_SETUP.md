# NGỌC SĨ AI — Firebase AI Logic setup (phone-friendly)

This guide is for the isolated preview branch `ngocsiAI`. It uses the official Android Firebase AI Logic SDK, not the separate Cloud Functions proxy experiment.

## 1. Confirm the right Firebase project

1. Open https://console.firebase.google.com/ on your phone. If the menu is difficult to see, use Chrome ⋮ → **Desktop site**.
2. Select the existing project and verify **Project ID** is exactly `ngoc-si-music-ai`.
3. Do not create another project or deploy anything to a different project.
4. Keep the current production app `com.ngocsi.music` registered. Add a separate Android app with package `com.ngocsi.music.aipreview` for the isolated preview if it is not already listed.

## 2. Enable Firebase AI Logic

1. In the project, open **AI Services → AI Logic → Get started**.
2. Choose **Gemini Developer API** and complete the guided setup. This enables the APIs Firebase AI Logic requires.
3. Do not generate or embed a Gemini API key for the Android app. The Firebase AI Logic SDK uses Firebase client configuration and App Check. Never use the Firebase API key as a Gemini API key.
4. Review the model's current free-tier availability, per-project quotas, and data-use terms. Do not send passwords, verification codes, financial data, or other sensitive messages.

Official guide: https://firebase.google.com/docs/ai-logic/get-started?platform=android.

## 3. Configure App Check

1. Open **Security → App Check → Apps** and select the `com.ngocsi.music.aipreview` app.
2. The current debug preview code uses the **App Check Debug provider** (`BuildConfig.DEBUG`), not Play Integrity. After an intentionally approved debug preview is installed and launched, retrieve its debug token from Android Studio/Logcat or the app's Firebase App Check debug log, then register that exact token for the preview app in Firebase Console. Never post the token in chat or commit it to GitHub.
3. The stable preview SHA-256 fingerprint `75:82:A1:F9:00:4E:B8:3A:24:84:FC:E5:B1:EC:13:9E:D2:57:14:3F:AA:E5:8C:DA:D1:B7:C2:9F:A2:04:E6:5E` applies to Play Integrity signing setup only; reconfirm it against a deliberately built preview before using Play Integrity. A sideloaded debug build needs its debug token registered.
4. Review **Security → App Check → APIs** for Firebase AI Logic. Keep enforcement enabled once the appropriate provider/token is correctly registered; do not disable enforcement to bypass a failed request.

Official guide: https://firebase.google.com/docs/app-check/android/play-integrity-provider.

## 4. Configure GitHub Actions settings

This branch currently gets Firebase client values from repository Actions secrets. It does not automatically consume `google-services.json`.

In GitHub mobile: repository → **Settings → Secrets and variables → Actions**. Add or verify:

| Secret name | Value |
| --- | --- |
| `FIREBASE_API_KEY` | `api_key[0].current_key` in Firebase config |
| `FIREBASE_PROJECT_ID` | `project_info.project_id` = `ngoc-si-music-ai` |
| `FIREBASE_SENDER_ID` | `project_info.project_number` |
| `FIREBASE_APP_ID` | `mobilesdk_app_id` for the production package `com.ngocsi.music` (required by the CI validator even though preview variants override it) |
| `FIREBASE_DEBUG_APP_ID` | `mobilesdk_app_id` for package `com.ngocsi.music.aipreview` |

Do not send these values in chat. The preview app ID is not the same as the production app ID. If any secret is missing, this branch intentionally stays offline.

## 5. Model and quota protection

The Android preview uses `gemini-3.5-flash-lite` for LOW-intent requests: routine chat, factual/knowledge questions, short low-intent follow-ups, and single app actions. Firebase's current thinking-level table lists `MINIMAL` as the only supported level for Flash-Lite, so every request routed there is sent with `MINIMAL`; sending `LOW` or `MEDIUM` can cause a request-configuration error. MEDIUM/HIGH reasoning tasks—including detailed analysis, analytical follow-ups, comparisons, video analysis, explicit audio-evidence checks such as “dấu hiệu nghe được trong bản thu”, and compound multi-action requests—use `gemini-3.8-flash` with `MEDIUM` by default. Short follow-ups that carry an attached YouTube video keep this MEDIUM multimodal route and receive the longer video-processing deadline. `HIGH` is reserved for prompts that explicitly request maximum reasoning. This gives complex tasks an actual supported reasoning budget without increasing thinking for simple commands. Automatic remote model fallback remains disabled; eligible text-only failures may use the on-device Qwen/rule fallback under the separate safety rules.

Quota cooldowns are stored per model. A limit reached by `gemini-3.8-flash` must not block a later request to `gemini-3.5-flash-lite`. The previous global cooldown from older Preview versions is migrated to the old default model only. Everyday questions use a 384-token output cap; medium tasks use 1,024 tokens; high-depth tasks use 2,048 tokens. Conversation history and saved-memory context are bounded. These optimizations do not create unlimited quota: RPM, RPD, TPM, TPD, model capacity, and project-level limits still apply across all apps using the same Firebase project. Inspect the active limits for the exact model in AI Studio. Do not enable billing simply to bypass `AI_QUOTA_429`.

The optional local Qwen3-0.6B fallback runs through LiteRT-LM on the phone and is never uploaded to a cloud provider. It is deliberately skipped for YouTube video/audio evidence analysis in both screen chat and hands-free wake-word sessions, because the local model is text-only; the active video ID and metadata are passed from the YouTube page into the hands-free service before each Gemini request. Its conversation disables Qwen3's default thinking mode and caps output at 384 tokens for simple replies or 640 tokens for contextual follow-ups/explicit analysis, keeping the small local model focused and limiting unnecessary decode time. These are response-generation limits, not a claim that local Qwen3 has Gemini-level reasoning quality. The model must first be explicitly downloaded by the user; local fallback is skipped for app-control tool actions and multimodal YouTube analysis.\n\nSince November 2, 2026, Firebase AI Logic requires App Check enforcement. The debug Preview therefore also needs a valid Debug App Check token registered for the exact `com.ngocsi.music.aipreview` Firebase app. If App Check rejects the request, switching models will not fix it; inspect Firebase Console → App Check → Firebase AI Logic metrics and use the error diagnostic shown by the app. Never share the debug token in chat.

## Video-path diagnostic added in Preview 6.11

From the YouTube page or any NGỌC SĨ AI chat, send this exact request:

`AI, kiểm tra kết nối video mẫu.`

The app sends Google's public MP4 sample (`animals.mp4`) through the same Firebase AI Logic multimodal request path. It does not pause, replace, or otherwise control the YouTube player.

Interpret the result:

- If the sample video is analyzed and NGỌC SĨ AI describes what is actually visible, Firebase AI Logic, the selected Gemini model, App Check, and the MP4 URL path are working. If a YouTube video still fails, check that the individual YouTube video is public or unlisted and available to Google's processing service.
- If the sample returns `VIDEO_SAMPLE_SERVER_ERROR`, the problem is not specific to the active YouTube URL. Check Firebase AI Logic availability, App Check verification for the exact Preview app, and model access/quota.
- If only a YouTube URL returns `YOUTUBE_VIDEO_SERVER_ERROR`, the server did not expose an HTTP status; run the sample test before drawing conclusions.

This diagnostic is explicit opt-in and does not run on ordinary chat or playback commands. It does not enable billing or change the production app.

## 6. Test only after a preview is built intentionally

The latest Preview commit before this update passed GitHub Actions Android lint, unit tests, debug APK packaging, 16 KB native-library alignment checks, Preview-package isolation checks, and APK-signature verification. This does not prove live Firebase/App Check access or real-device speech and background behavior. On the phone, verify in order: install the Preview alongside the stable app; open the AI screen; submit one short typed question; verify TTS; test local media commands; test YouTube while foregrounded and with the screen off; then test quota/network failure behavior. A local-command success does not prove the online model works.

This SDK path does not require `GEMINI_API_KEY`, `firebase functions:secrets:set`, or `firebase deploy`. The Cloud Functions files are a separate retained experiment, not part of this branch's Android chat request path. GitHub Actions checks source, unit tests, package isolation, and APK signing; only a real phone test can confirm live Firebase responses and App Check behavior.

## Free-tier note

The Gemini Developer API has model-specific free-tier limits and data terms that can change. A free tier is not unlimited and cannot be guaranteed to remain free in all use cases. Check current pricing, quotas, and terms before enabling online use: https://firebase.google.com/docs/ai-logic/rate-limits and https://firebase.google.com/docs/ai-logic/pricing.
