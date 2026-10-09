# NGỌC SĨ AI — secure backend setup

## Architecture

Use Firebase Cloud Functions (2nd gen) in `asia-southeast1`, Firebase Authentication, Firebase App Check, Firestore daily quotas, and a provider key stored as a Cloud Functions secret. The Android APK must call the callable function; it must never contain the provider API key.

The initial provider adapter targets OpenAI Chat Completions with the fixed server-side model `gpt-4o-mini`. The mobile app cannot choose arbitrary upstream URLs or model names.

## Security and low-cost defaults

- Firebase callable endpoint; authenticated user required.
- Firebase App Check enforcement is enabled on the AI callable. The Android client must obtain valid App Check tokens before the feature can work.
- Provider key loaded from Secret Manager using `defineSecret`; never commit it or put it in Android BuildConfig.
- Maximum 8 conversation messages, 2,000 characters per message, 8,000 total characters, only user/assistant roles, and last message must be from the user.
- Per-user daily limit: 10 calls; response token cap: 600.
- Quota reservation is transactional to prevent concurrent calls exceeding the per-UID limit. A failed provider call still consumes a quota slot, preventing repeated retries from creating uncontrolled cost.
- Usage records contain UID/date/count only; chat content is not stored by this function.
- The quota day resets at 00:00 UTC (07:00 in Vietnam). Anonymous users can create a new UID by clearing app data/reinstalling, so per-UID limits are not a complete anti-abuse boundary; App Check and provider-side monitoring are still required.
- Upstream errors and response bodies are not returned to the client.
- Existing Android and release workflows remain unchanged until mobile integration and end-to-end checks pass.

## Phone-friendly Firebase setup

Use Chrome on your phone. If a page is hard to navigate, open Chrome's menu (⋮) and enable **Desktop site**.

1. Open https://console.firebase.google.com/ and sign in to Google. Do not share your password or verification codes.
2. Tap **Create a project**. Give it a name such as `NGOC-SI-MUSIC-AI`. Google Analytics is not required for this MVP; leave it off to reduce setup complexity.
3. Wait for project creation. In Project settings, copy the **Project ID** (this is not a password). Keep it private if preferred; it is okay to provide this identifier when needed.
4. On the project overview, choose **Add app → Android**. Enter package name exactly: `com.ngocsi.music`. App nickname can be `NGỌC SĨ MUSIC`. SHA-1 is not normally required just to register Firebase Android app, but Play Integrity/App Check setup may require app/signing information later.
5. Download `google-services.json`. Do not upload it to a public issue or paste its contents into chat. Keep it on your phone until we give you a safe way to add it to the private repository or configure CI.
6. Open **Build → Authentication → Get started → Sign-in method → Anonymous**, enable it, and save.
7. Open **Build → App Check** and register the Android app with **Play Integrity**. Do not weaken or disable App Check in production. Debug testing may require a separate debug provider/token; never include a debug token in a release APK.
8. Open **Firestore Database** and create the database only when prompted. Choose a nearby region consistent with the functions region where possible. The function uses `asia-southeast1`. Do not deploy the provided rules file until checking whether the project has any existing collections/rules; Firestore rules are project-wide.
9. In Google Cloud/Firebase billing, attach the required billing account if Firebase asks for Blaze. Set budget alerts before deployment. A budget alert is not a guaranteed hard spending cap.
10. Create an OpenAI API project/key in the OpenAI platform, enable API billing, and set the tightest available project usage limits/alerts. Never send the key to this chat or commit it to GitHub.
11. Once Firebase project creation is complete, deployment should happen from a trusted environment or a reviewed CI deployment workflow using short-lived identity. Do not store a long-lived Google service-account JSON key in the repository.

## Server deployment (not yet performed)

After the Firebase project exists and its ID is confirmed, a trusted deploy environment must:
- select the correct Firebase project;
- set the server secret with the Firebase CLI prompt, e.g. `firebase functions:secrets:set OPENAI_API_KEY` (paste the key only into the trusted CLI prompt);
- deploy only the function first: `firebase deploy --only functions:ngocSiAiChat`;
- review and deploy Firestore rules separately only after confirming they do not block other app features;
- test authentication, App Check, quota exhaustion, provider failure, and a real OpenAI response.

The function is currently not deployed. CI does not deploy it.

## Required before Android integration

- Firebase Project ID.
- Android Firebase app registration and `google-services.json` added through a safe private route.
- Anonymous Authentication enabled.
- Play Integrity App Check configured for the actual release signing certificate.
- Provider account with API billing enabled and key stored only in Secret Manager.
- Budget alerts and a confirmed quota (current default: 10 requests per UID per UTC day).
- Decide a trusted deployment path. Do not put a long-lived Google service-account JSON key in the repo.

## CI and release policy

The backend workflow runs on backend changes and pull requests. It type-checks and tests input validation; it does not deploy functions or publish APKs. Deployment and app release remain separate explicit steps. Android integration must be built and tested on a separate branch; publish only after Android lint, unit tests, debug/release builds, package/signature checks, backend integration tests, and an on-device end-to-end check pass.

## Android integration design

Add Firebase Auth, Firebase Functions, and App Check SDKs to Android only after the Firebase project config is available. Add a dedicated NGỌC SĨ AI screen and call the callable function through the Firebase SDK. Voice input should use Android speech recognition as an opt-in microphone action; text is sent to the backend only after the user taps send. Music actions must be mapped locally through an allowlisted intent layer (play/pause/next/previous/sleep timer), never by executing arbitrary AI-generated commands. Do not change existing playback, Drive, Radio, TV, or release behavior during the first integration step.
