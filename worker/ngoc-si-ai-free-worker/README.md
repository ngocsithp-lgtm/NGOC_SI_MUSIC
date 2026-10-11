# NGỌC SĨ AI Free Worker

This Worker is an optional online AI gateway for the isolated Android preview branch. It is **not deployed by default**. Keep the production `main` branch and production APK unchanged.

## Required Cloudflare Worker secrets and variables

Set these only in the Cloudflare dashboard / secret store, never in this repository:

- Secret `GEMINI_API_KEY`: provider API key. Do not put this in Android BuildConfig or APK.
- Variable `FIREBASE_PROJECT_ID`: exact Firebase project ID (currently expected by the Android preview to be `ngoc-si-music-ai`).
- Variable `FIREBASE_PROJECT_NUMBER`: numeric project number from Firebase project settings.
- Variable `FIREBASE_APP_ID`: Firebase Android app ID registered for the preview package `com.ngocsi.music.aipreview`.

The Firebase Android app must be registered with the preview package name and configured for App Check. The preview APK must be signed with the certificate whose SHA-256 is printed by the Android integration CI. Register that SHA-256 with the corresponding Firebase App Check / Play Integrity configuration as required by Firebase.

## Required Android build-time values

The isolated preview build reads these environment values from the build job:

- `FIREBASE_API_KEY`
- `FIREBASE_PROJECT_ID`
- `FIREBASE_SENDER_ID`
- `FIREBASE_DEBUG_APP_ID`
- `NGOC_SI_AI_WORKER_URL` (the deployed Worker base URL, without `/v1/chat`)

These are injected at build time through repository Actions secrets/variables; never commit credentials. The Gemini provider key is server-only and must not be supplied to Android.

## Deployment safety

The GitHub deployment workflow is gated by the repository variable `ENABLE_NGOC_SI_AI_WORKER_DEPLOY=true`. Leave it unset/false until the Firebase variables, App Check registration, server-side Gemini secret, and Android build configuration have been reviewed and tested. A successful CI run tests the Worker code only; it does not mean the online service is deployed or ready.

## Local verification

From this directory, run:

```sh
npm test
```

The tests verify request validation and that chat requests without Firebase ID token and App Check are rejected. Live token verification and end-to-end device testing are still required before production use.

## Limits and cost

Provider free quotas and terms can change. The configured rate limits are best-effort safeguards, not a guarantee of unlimited or permanently free service. Never expose provider keys in logs, client code, or APKs.
