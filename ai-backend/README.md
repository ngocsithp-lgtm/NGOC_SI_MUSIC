# NGỌC SĨ AI backend — preparation only

This folder is a **not-yet-deployed** Node.js backend scaffold. It is deliberately not connected to the Android app yet.

## Security model

- The Android app must send a Firebase Authentication ID token in `Authorization: Bearer <token>`.
- The backend verifies the token before calling Gemini. Never trust a user ID supplied in JSON.
- Gemini credentials are read only from `GEMINI_API_KEY` at runtime. Do not put real credentials in this repository, the APK, BuildConfig, logs, or client-side code.
- In Cloud Run, store the Gemini key in Secret Manager and grant the Cloud Run service account access to that one secret only.
- Configure Firebase Authentication and an appropriate app attestation/App Check policy before public use.
- Request-size validation and per-instance rate limiting are included as basic safeguards, not a substitute for account-level quotas, billing alerts, monitoring, or distributed abuse controls.

## Current limitations

- Not deployed to Cloud Run.
- Not wired into NGỌC SĨ MUSIC.
- Requires a Firebase project with Authentication configured and a trusted client sign-in flow before the endpoint can be used.
- A real Gemini API key is intentionally not included.
- Do not enable billing just to test this scaffold.

## Local preparation

1. Install Node.js 20 or later in a trusted development environment.
2. Run `npm install`.
3. Configure Firebase Admin Application Default Credentials for a test Firebase project.
4. Set `GEMINI_API_KEY` in the process environment (never commit it) and optionally `GEMINI_MODEL`.
5. Run `npm test`, then `npm start`.

This repository scaffold is not a production security review. Before deployment, configure a restrictive Cloud Run ingress/auth policy, Secret Manager, budgets/alerts, request quotas, and Firebase App Check or an equivalent abuse-prevention layer.
