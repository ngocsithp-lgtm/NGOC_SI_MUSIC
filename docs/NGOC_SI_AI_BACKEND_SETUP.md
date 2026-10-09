# NGỌC SĨ AI — secure backend setup

## Architecture

Use Firebase Cloud Functions (2nd gen) in `asia-southeast1`, Firebase Authentication, Firebase App Check, Firestore daily quotas, and a provider key stored as a Cloud Functions secret. The Android APK must call the callable function; it must never contain the provider API key.

The initial provider adapter targets OpenAI Chat Completions with a fixed server-side model (`gpt-4o-mini`). The mobile app cannot choose arbitrary upstream URLs or model names.

## Security controls

- Firebase callable endpoint; authenticated user required.
- Firebase App Check enforcement enabled.
- Provider key loaded from Secret Manager using `defineSecret`; never commit it or put it in Android BuildConfig.
- Maximum 12 conversation messages, 4,000 characters per message, only user/assistant roles, and last message must be from the user.
- Per-user daily limit: 30 calls; response token cap: 1,000.
- No raw upstream error bodies or secrets returned to the client.
- Usage records contain UID/date/count only; chat content is not stored by this function.
- Existing Android and release workflows remain unchanged until mobile integration and end-to-end checks pass.

## Required owner setup (phone-friendly)

1. Create/select a Firebase project at https://console.firebase.google.com/. Enable billing for Cloud Functions/Firestore and set a budget alert first. Firebase may require the Blaze plan even for low traffic.
2. Register the Android app with package `com.ngocsi.music`.
3. Enable **Authentication → Sign-in method → Anonymous** for MVP, or choose a sign-in method if you want accounts to sync across devices.
4. Register **App Check** for the Android app and configure Play Integrity. Do not enforce it on any other functions or enable production enforcement until the Android app obtains valid App Check tokens. Use Firebase's debug provider only for debug builds; never ship a debug token in release.
5. Create an AI-provider API key and configure it as Firebase secret `OPENAI_API_KEY` using the Firebase CLI prompt. Never paste it into GitHub issues, chat, source code, or Android settings.
6. Deploy after checking the project ID and budget: `firebase deploy --only functions:ngocSiAiChat`.
7. Keep Firestore rules denying client access to `aiDailyUsage`. This repository includes `firestore.rules`; deploy rules after reviewing other app collections/rules.
8. Android app configuration (Firebase project/app ID) may be added to the client build. The provider API key stays server-side.

## Needed before deployment

- Firebase project ID (not a password).
- Firebase Android app registration and `google-services.json`.
- Owner's choice to enable anonymous auth or use a real sign-in.
- Provider account with API billing enabled and a server-side key stored in Secret Manager.
- Budget alert and quota confirmation (starter default is 30 calls/user/day).
- One deployment method: Firebase CLI on a trusted environment or a reviewed CI deploy workflow with Workload Identity Federation. Do not put a long-lived Google service-account JSON key in the repo.

## CI and release policy

The backend workflow runs on backend changes and pull requests. It type-checks and tests input validation; it does not deploy functions or publish APKs. Deployment and app release remain separate explicit steps. Android integration must be built and tested on a separate branch; publish only after Android lint, unit tests, debug/release builds, package/signature checks, and AI integration tests all pass.

## Next phase

This scaffold does not yet add the AI screen or connect voice recognition to AI. Next add a dedicated NGỌC SĨ AI page and call this callable function through Firebase SDK, with an opt-in microphone action using Android speech recognition. Music actions should be mapped locally through an allowlisted intent layer (play/pause/next/previous/sleep timer), never by executing arbitrary AI-generated commands.
