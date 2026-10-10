# NGỌC SĨ AI — secure backend setup

## Chosen architecture

Use Firebase Cloud Functions (2nd gen) in `asia-southeast1`, Firebase Anonymous Authentication, Firebase App Check, Firestore daily quotas, and Google Gemini Developer API. The Android APK calls the Firebase callable function; it must never contain a Gemini API key.

The server currently targets `gemini-2.5-flash-lite`, a lightweight model listed by Google's official pricing page with a free tier. Free-tier availability, rate limits, supported models, and data-use terms can change and vary by project. Check the live limits in Google AI Studio before relying on free usage. Google's pricing page says free-tier content may be used to improve its products; do not send sensitive personal information through the free tier. Source: https://ai.google.dev/gemini-api/docs/pricing

## Security and low-cost defaults

- Firebase callable endpoint; authenticated UID required.
- Firebase App Check enforcement is enabled on the AI callable. Android must obtain valid App Check tokens before the feature can work.
- Gemini API key loaded from Secret Manager using `defineSecret("GEMINI_API_KEY")`; never commit it or put it in Android BuildConfig.
- Maximum 8 conversation messages, 2,000 characters per message, 8,000 total characters, only user/assistant roles, and last message must be from the user.
- Per-user daily limit: 10 calls; server-wide daily limit: 50 calls across all users; maximum output: 600 tokens.
- Both quotas are reserved in one Firestore transaction so concurrent calls cannot exceed either limit. A provider failure after reservation still consumes a slot to discourage retry abuse; missing server configuration is checked before quota reservation.
- Usage records contain UID/date/count only; chat content is not stored by this function.
- Quota day resets at 00:00 UTC (07:00 in Vietnam).
- Anonymous users can create a new UID after clearing app data/reinstalling, so UID quotas are not a complete anti-abuse boundary. App Check and provider monitoring remain necessary.
- Upstream response bodies and keys are not returned to clients.
- Gemini 429 responses are converted to a safe rate-limit message.
- Existing Android and release workflows remain unchanged until mobile integration and end-to-end checks pass.

## Phone-friendly Firebase setup

Use Chrome on your phone. If a page is difficult to navigate, open Chrome's menu (⋮) and enable **Desktop site**.

1. Open https://console.firebase.google.com/ and sign in to Google. Never share your password or verification codes.
2. Select the existing Firebase project with Project ID `ngoc-si-music-ai` if it is still available in your account. Do not create a duplicate project. Only create a new project if that existing project is missing and you have verified which project contains the Android app registration and Firestore database. Google Analytics is not required for this MVP.
3. In Project settings, verify the **Project ID** is `ngoc-si-music-ai`. A project ID is an identifier, not a password. Stop here if the ID differs; do not deploy the backend into an unverified project.
4. Choose **Add app → Android** and enter package name exactly: `com.ngocsi.music`. Download `google-services.json` and keep it private until we provide a safe way to add it to the private repository or configure CI.
5. Open **Build → Authentication → Get started → Sign-in method → Anonymous → Enable → Save**.
6. Open **Build → App Check**, register the Android app, and select **Play Integrity**. Do not enforce production App Check until the actual app signing configuration is verified.
7. Create Firestore only when prompted. The function uses `asia-southeast1`. Before deploying any Firestore rules, review existing rules/collections; rules apply project-wide.
8. Review Firebase billing requirements. Cloud Functions may require the Blaze plan. Configure budget alerts, but do not treat alert emails as a hard cap: Firebase documents separate spend-cap budgets for selected services, including Cloud Functions for Firebase. Where the option is available, set a small Cloud Functions spend cap too. This does not guarantee a cap on Gemini Developer API charges or every other project service. Keep the server-wide 50-request/day limit enabled; it limits AI requests but is not a financial guarantee. See https://firebase.google.com/docs/projects/billing/budget-alerts.
9. Open https://aistudio.google.com/ to create a Gemini API key for the correct Google Cloud project. Do not send it to this chat, put it in Android, or commit it to GitHub. Check the model's free-tier limits and data-use terms first.
10. Deploy from a trusted environment or reviewed CI using short-lived identity. Do not store long-lived service-account JSON in the repository.

## Server deployment (not yet performed)

Once the Firebase project exists and its ID is confirmed, a trusted deploy environment must:
- select the intended Firebase project;
- set the secret using the trusted CLI prompt: `firebase functions:secrets:set GEMINI_API_KEY` (paste the key only into that prompt);
- deploy only the function: `firebase deploy --only functions:ngocSiAiChat`;
- review and deploy Firestore rules separately, only after confirming they do not block existing app features;
- test anonymous authentication, App Check, quota exhaustion, provider 429/failure, and a real Gemini response.

The function is not deployed. CI does not deploy it and does not make external Gemini API calls.

## Required before Android integration

- Firebase Project ID.
- Android Firebase app registration and `google-services.json` added through a safe private route.
- Anonymous Authentication enabled.
- Play Integrity App Check configured for the actual release signing certificate.
- Gemini API project/key stored only in Secret Manager.
- Current free-tier rate limits and data-use terms reviewed in AI Studio.
- Budget alerts and app-side quota (10 requests per UID per UTC day).
- Trusted deployment path. No long-lived service-account JSON in the repository.

## Android integration design

Add Firebase Auth, Firebase Functions, and App Check SDKs to Android only after the Firebase project configuration is available. Add a dedicated NGỌC SĨ AI screen and call the callable function through Firebase SDK. Voice input should use Android speech recognition as an opt-in microphone action; send recognized text only after the user taps send. Music actions must map locally through an allowlisted intent layer (play/pause/next/previous/sleep timer), never execute arbitrary AI-generated commands. Do not change existing playback, Drive, Radio, TV, or release behavior in the first integration step.

## CI and release policy

The backend workflow type-checks, runs unit tests, and checks source for common OpenAI/Gemini key patterns. It does not deploy functions or publish APKs. This is not end-to-end proof of Firebase/OpenAI/Gemini connectivity. Android integration must remain on a separate branch and release only after Android lint, unit tests, debug/release builds, package/signature checks, backend integration tests, and an on-device end-to-end check pass.
