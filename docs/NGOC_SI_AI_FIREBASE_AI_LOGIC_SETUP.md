# NGỌC SĨ AI — Firebase AI Logic setup (phone-friendly)

This guide is for the isolated branch `feature/ngoc-si-ai-firebase-ai-logic-preview`. It uses the official Android Firebase AI Logic SDK, not the separate Cloud Functions proxy experiment.

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

The preview uses `gemini-3.5-flash-lite`, which Firebase lists as a stable Gemini Flash-Lite model that does not require the Blaze pay-as-you-go plan when used with the Gemini Developer API. Responses are capped at 768 output tokens, thinking effort is set to minimal, and conversation context is limited to six messages / 4,000 characters. These limits reduce typical usage but cannot guarantee unlimited free requests: provider quotas and model capacity still apply. Do not enable billing simply to bypass `AI_QUOTA_429`.

## 6. Test only after a preview is built intentionally

The present change has **not** been built or run on a phone. After an approved preview build is made, verify in order: the AI page opens; a typed question returns a live answer; the answer can be read aloud by Android TTS; a supported music command executes locally; App Check, network, and quota errors show a useful message. A local-command success does not prove the online model works.

This SDK path does not require `GEMINI_API_KEY`, `firebase functions:secrets:set`, or `firebase deploy`. The Cloud Functions files are a separate retained experiment, not part of this branch's Android chat request path. GitHub Actions checks source, unit tests, package isolation, and APK signing; only a real phone test can confirm live Firebase responses and App Check behavior.

## Free-tier note

The Gemini Developer API has model-specific free-tier limits and data terms that can change. A free tier is not unlimited and cannot be guaranteed to remain free in all use cases. Check current pricing, quotas, and terms before enabling online use: https://firebase.google.com/docs/ai-logic/rate-limits and https://firebase.google.com/docs/ai-logic/pricing.
