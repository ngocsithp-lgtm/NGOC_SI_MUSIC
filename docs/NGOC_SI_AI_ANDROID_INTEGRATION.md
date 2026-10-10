# NGỌC SĨ AI — Android integration branch

**Status: experimental, unmerged, and not deployed.** This isolated preview is based on the current `main` baseline, includes the chat UI, Firebase callable client, and backend source, and keeps the production package and APK workflow separate.

## Behaviour and safety boundaries

- If Firebase client settings are absent, the app stays in local preview mode. It does not call Firebase or Gemini.
- The recent chat transcript is stored only in this app's private local preferences on the device (up to 60 visible messages); the user can clear it through the confirmed **XÓA** action. The app does not sync the transcript to cloud storage.
- Playback and status commands are handled locally through this Preview app's own Media3 session: **Phát nhạc**, **Tạm dừng nhạc**, **Bài tiếp theo**, **Bài trước**, **Đang phát bài gì?**, **Phát ngẫu nhiên**, **Tắt phát ngẫu nhiên**, **Lặp hàng đợi**, **Lặp một bài**, and **Tắt chế độ lặp**. After the user grants Android audio-library permission, Preview loads eligible local-device music into its own queue without starting playback. This queue is separate from the production NGỌC SĨ MUSIC app and does not edit that app's queue. These commands do not call Firebase/Gemini or consume AI quota. Only recognized phrases matching an allowlisted command trigger a local action; unrelated phrases never trigger playback actions. Matching shortcuts are also shown above the input field.
- When online mode is configured, a request sends only the recent conversation context (up to 8 messages and 8,000 total characters) to the callable backend. Do not enter passwords, verification codes, or other sensitive data.
- When the user taps the microphone button, the recognized phrase is submitted through the same handler as typed text. Supported local playback commands run on-device without calling Firebase. Other messages are sent to the online chat only if Firebase client settings are configured; otherwise the app clearly explains that free-form AI is unavailable in this offline preview. Check the text before sending sensitive information; never enter passwords or verification codes.
- If Firebase settings are present, online chat signs in anonymously and calls the `ngocSiAiChat` Firebase callable in `asia-southeast1`. A spoken question can be sent to that backend after recognition because the user explicitly tapped the microphone button.
- The experimental **wake phrase** feature is a separate, user-started foreground microphone service. The user grants microphone permission and taps **Bật nghe từ khóa “Ngọc Sĩ”** once while the app is visible; an ongoing notification shows that the microphone is active and provides a stop action. Commands are accepted only when the recognized utterance begins with **“Ngọc Sĩ”** and exactly matches a supported local playback command. The service uses Android's on-device speech recognizer only, requests offline recognition, and stops with an explanatory notification if on-device recognition or the Vietnamese language pack is unavailable. It does not silently fall back to a network recognizer. This is a best-effort continuous-listening prototype, not a dedicated low-power wake-word model; battery use and Vietnamese offline availability vary by device. It is not automatically enabled after reboot.
- Android uses Firebase Authentication, Firebase Functions, and App Check with Play Integrity. It does not include a Gemini API key.
- Gemini requests run on the server. The server-side key must be stored as the Firebase Functions secret `GEMINI_API_KEY`, never as an Android setting or GitHub Android build secret.
- This branch has no deployment step and does not publish a production APK. Do not merge it until CI passes and a configured APK has been checked on a real device.

## Use the existing Firebase project

Use the existing project ID `ngoc-si-music-ai` if it is still visible in your account. Do not create another Firebase project just for this branch. The existing production Android app is `com.ngocsi.music`.

The preview APK is intentionally a separate Android package, `com.ngocsi.music.aipreview`, so it can be installed alongside production. In Firebase Console, add a second Android app to the same project with that exact package name. This gives the preview app its own Firebase App ID without changing the production app registration.

Anonymous Authentication must be enabled for the project. Do not enable the online test until the backend function exists and the Firebase billing implications are understood.

## App Check for a sideloaded preview APK

1. Open the integration branch's GitHub Actions run after a build.
2. In its **Summary**, find **Firebase App Check — preview build** and copy the reported signing certificate SHA-256.
3. In Firebase Console → App Check, register the `com.ngocsi.music.aipreview` Android app with Play Integrity and add that SHA-256.
4. Because this debug APK is installed from a downloaded CI artifact rather than Google Play, review the Play Integrity advanced settings for distribution outside Google Play. Do not require the Play Store recognition/licensing verdicts for this sideloaded preview. Follow Google's current guide: https://firebase.google.com/docs/app-check/android/play-integrity-provider

The workflow uses the repository's stable CI signing certificate so the fingerprint is repeatable. If the stable signing secrets are missing, the workflow stops instead of producing a misleading fingerprint.

## Add Firebase client settings to GitHub Actions

Do this in GitHub on your phone: repository → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**. Do not paste any of these values into this chat or into source code.

Create these secrets:

| Secret name | Value to copy from |
| --- | --- |
| `FIREBASE_API_KEY` | `api_key[0].current_key` in the downloaded Firebase Android config |
| `FIREBASE_PROJECT_ID` | `project_info.project_id` (must be `ngoc-si-music-ai`) |
| `FIREBASE_SENDER_ID` | `project_info.project_number` |
| `FIREBASE_APP_ID` | `client_info.mobilesdk_app_id` for package `com.ngocsi.music` |
| `FIREBASE_DEBUG_APP_ID` | `client_info.mobilesdk_app_id` for package `com.ngocsi.music.aipreview` |

These are Firebase **client configuration values**, not the Gemini API key. Client values are included in the compiled app, so restrict the Firebase API key to the APIs the app needs and rely on Authentication, App Check, and server-side quota checks for access control.

After saving the secrets, open the `NGOC SI AI Android Integration CI` workflow for branch `feature/ngoc-si-ai-complete-preview` in GitHub Actions. A push to the preview branch also runs the validation workflow. The absence of Firebase client secrets is deliberately tolerated by Gradle: the APK remains in local preview mode.

## Backend deployment and costs

The backend source is included in the isolated preview branch and is independently type-checked by `ai-backend-ci.yml`. It must still pass review before deployment. No cloud function is deployed by either CI workflow.

Before a real online test, the backend must be deployed to the confirmed Firebase project and the Gemini key must be stored using a trusted Firebase CLI prompt:

```text
firebase functions:secrets:set GEMINI_API_KEY
firebase deploy --only functions:ngocSiAiChat
```

Do not put the Gemini key in GitHub Secrets used by Android builds, the APK, or the repository. Confirm Firebase billing requirements before deployment; Cloud Functions may require the Blaze plan. Configure budget alerts and, where available, a Cloud Functions spend-cap budget. Alerts alone do not stop billing, and a Cloud Functions spend cap does not necessarily cap every Gemini API charge.

Server guardrails currently include 10 calls per UID per UTC day, a global ceiling of 50 requests per UTC day, up to 8 history messages, a 2,000-character per-message limit, an 8,000-character total input limit, and at most 600 output tokens. These are usage limits, **not a guarantee of zero charges**. Gemini's free-tier data-use terms can allow submitted content to be used to improve Google's products. Do not send passwords, verification codes, financial details, or other sensitive content.

## Validation status

This branch must pass Android lint, JVM unit tests, debug assembly, APK package isolation, and stable signing fingerprint extraction. A real online response still requires the Firebase app registrations, Actions secrets, App Check configuration, deployed backend, and a user-initiated device test. No end-to-end AI success should be claimed before that test passes.
