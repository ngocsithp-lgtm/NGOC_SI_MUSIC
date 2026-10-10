# NGỌC SĨ AI — Android integration branch

**Status: experimental, unmerged, and not deployed.** This branch builds on the locally tested chat UI, adds a Firebase callable client, and keeps the current production app untouched. The main UI Preview PR and the backend PR remain separate.

## Behaviour and safety boundaries

- If Firebase client settings are absent, the app stays in local preview mode. It does not call Firebase or Gemini.
- The recent chat transcript is stored only in this app's private local preferences on the device (up to 60 visible messages); the user can clear it through the confirmed **XÓA** action. The app does not sync the transcript to cloud storage.
- When online mode is configured, a request sends only the recent conversation context (up to 8 messages and 8,000 total characters) to the callable backend. Do not enter passwords, verification codes, or other sensitive data.
- If Firebase settings are present, sending a message signs in anonymously and calls the `ngocSiAiChat` Firebase callable in `asia-southeast1`. The microphone only fills the text box; it does not submit or send by itself.
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

After saving the secrets, run the workflow manually from branch `feature/ngoc-si-ai-android-integration` in GitHub Actions. The absence of these secrets is deliberately tolerated by the Gradle configuration: the APK remains in local preview mode.

## Backend deployment and costs

The backend implementation is maintained separately in PR #6: https://github.com/ngocsithp-lgtm/NGOC_SI_MUSIC/pull/6. It must pass review before it is merged or deployed. No cloud function is deployed by this Android workflow.

Before a real online test, the backend must be deployed to the confirmed Firebase project and the Gemini key must be stored using a trusted Firebase CLI prompt:

```text
firebase functions:secrets:set GEMINI_API_KEY
firebase deploy --only functions:ngocSiAiChat
```

Do not put the Gemini key in GitHub Secrets used by Android builds, the APK, or the repository. Confirm Firebase billing requirements before deployment; Cloud Functions may require the Blaze plan. Configure budget alerts and, where available, a Cloud Functions spend-cap budget. Alerts alone do not stop billing, and a Cloud Functions spend cap does not necessarily cap every Gemini API charge.

Server guardrails currently include 10 calls per UID per UTC day, a global ceiling of 50 requests per UTC day, up to 8 history messages, a 2,000-character per-message limit, an 8,000-character total input limit, and at most 600 output tokens. These are usage limits, **not a guarantee of zero charges**. Gemini's free-tier data-use terms can allow submitted content to be used to improve Google's products. Do not send passwords, verification codes, financial details, or other sensitive content.

## Validation status

This branch must pass Android lint, JVM unit tests, debug assembly, APK package isolation, and stable signing fingerprint extraction. A real online response still requires the Firebase app registrations, Actions secrets, App Check configuration, deployed backend, and a user-initiated device test. No end-to-end AI success should be claimed before that test passes.
