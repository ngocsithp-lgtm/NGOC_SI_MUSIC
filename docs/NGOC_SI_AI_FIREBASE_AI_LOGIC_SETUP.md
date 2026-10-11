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
2. Register **Play Integrity** and enter the SHA-256 from the stable preview signing certificate when a preview build is intentionally made.
3. Because this preview is expected to be sideloaded during testing, review Google's settings for apps distributed outside Google Play.
4. Open **Security → App Check → APIs** and make sure Firebase AI Logic is enforced. Do not disable enforcement to make a failed request work.

Official guide: https://firebase.google.com/docs/app-check/android/play-integrity-provider.

## 4. Configure GitHub Actions settings

This branch currently gets Firebase client values from repository Actions secrets. It does not automatically consume `google-services.json`.

In GitHub mobile: repository → **Settings → Secrets and variables → Actions**. Add or verify:

| Secret name | Value |
| --- | --- |
| `FIREBASE_API_KEY` | `api_key[0].current_key` in Firebase config |
| `FIREBASE_PROJECT_ID` | `project_info.project_id` = `ngoc-si-music-ai` |
| `FIREBASE_SENDER_ID` | `project_info.project_number` |
| `FIREBASE_DEBUG_APP_ID` | `mobilesdk_app_id` for package `com.ngocsi.music.aipreview` |

Do not send these values in chat. The preview app ID is not the same as the production app ID. If any secret is missing, this branch intentionally stays offline.

## 5. Test only after a preview is built intentionally

The present change has **not** been built or run on a phone. After an approved preview build is made, verify in order: the AI page opens; a typed question returns a live answer; the answer can be read aloud by Android TTS; a supported music command executes locally; App Check, network, and quota errors show a useful message. A local-command success does not prove the online model works.

This SDK path does not require `GEMINI_API_KEY`, `firebase functions:secrets:set`, or `firebase deploy`. The Cloud Functions files are a separate retained experiment, not part of this branch's Android chat request path.

## Free-tier note

The Gemini Developer API has model-specific free-tier limits and data terms that can change. A free tier is not unlimited and cannot be guaranteed to remain free in all use cases. Check current pricing, quotas, and terms before enabling online use: https://firebase.google.com/docs/ai-logic/rate-limits and https://firebase.google.com/docs/ai-logic/pricing.
