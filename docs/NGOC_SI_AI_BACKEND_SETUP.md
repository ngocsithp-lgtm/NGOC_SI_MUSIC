# NGỌC SĨ AI — secure backend setup

## Chosen architecture

Use Firebase Cloud Functions (2nd gen) in `asia-southeast1`, Firebase Anonymous Authentication, Firebase App Check, Firestore daily quotas, and Google Gemini Developer API. The Android APK calls the Firebase callable function; it must never contain a Gemini API key.

The server targets stable Gemini 3.5 Flash-Lite (`gemini-3.5-flash-lite`), which Google's current model guidance recommends for new projects. The official pricing page currently lists a free tier for standard model input/output; rates, access, per-project quotas, and data-use terms can change. Review current terms and limits in Google AI Studio before enabling any requests. Google may use free-tier content to improve its products, so do not send sensitive personal information. The app still uses Firebase Cloud Functions for the proxy, which requires the Blaze (pay-as-you-go) plan; no deployment or billing change has been performed. Sources: https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite and https://ai.google.dev/gemini-api/docs/pricing and https://firebase.google.com/docs/functions/quotas

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

## Required before enabling the secure Android backend

- Firebase Project ID.
- Android Firebase app registration and `google-services.json` added through a safe private route.
- Anonymous Authentication enabled.
- Play Integrity App Check configured for the actual release signing certificate.
- Gemini API project/key stored only in Secret Manager.
- Current free-tier rate limits and data-use terms reviewed in AI Studio.
- Budget alerts and app-side quota (10 requests per UID per UTC day).
- Trusted deployment path. No long-lived service-account JSON in the repository.

## Android integration design

The Android feature branch now includes an optional Firebase callable client. Gradle reads `NGOC_SI_AI_BACKEND_ENABLED=true` from the build environment and writes a `BuildConfig` flag. The default is **false** on purpose, so existing AI behavior is preserved until the callable is deployed and verified. With the flag enabled, the screen signs in anonymously if needed and calls `ngocSiAiChat` in `asia-southeast1`, sending a bounded alternating chat history; the provider key is never added to Android code. Do not enable this flag in an installable APK until Anonymous Authentication is enabled, the callable and Secret Manager key are deployed, Firestore is configured, and App Check is tested with the actual signing certificate. The code path compiles in CI but the callable has not been exercised end-to-end.

The feature branch also has a local allowlist for play/pause/next/previous, shuffle on/off, repeat one/all/off, sleep-timer presets/cancel, and navigation to Library, YouTube, Radio, queue, player, TV, Drive, and Settings. Voice recognition executes only a recognized allowlisted command immediately; ordinary spoken questions populate the input field and still require the user to tap Send. This local command parser must never execute arbitrary AI-generated instructions. Keep the work on the feature branch and do not merge/build a release APK before backend and on-device checks pass.

## CI and release policy

The backend workflow type-checks, runs unit tests, and checks source for common OpenAI/Gemini key patterns. It does not deploy functions or publish APKs. This is not end-to-end proof of Firebase/OpenAI/Gemini connectivity. Android integration must remain on a separate branch and release only after Android lint, unit tests, debug/release builds, package/signature checks, backend integration tests, and an on-device end-to-end check pass.


## Verified implementation status on `feature/ngoc-si-ai-backend-foundation`

This section takes precedence over the proposed architecture above when describing what is currently wired into the Android app:

- `NgocSiAiScreen.kt` has an opt-in server path via `NgocSiAiBackendClient.kt`; Gradle enables it only when `NGOC_SI_AI_BACKEND_ENABLED=true` is explicitly set in the build environment.
- If that flag is not set (the current default), the screen continues to use Firebase AI Logic directly with `GenerativeBackend.googleAI()` and `gemini-3.5-flash-lite`.
- `NgocSiMusicApplication.kt` installs the App Check debug provider for debug builds and Play Integrity for non-debug builds.
- `functions/src/index.ts` contains the callable backend with Secret Manager and server-side quotas, but the callable function has **not** been deployed. The secure path therefore must remain disabled until deployment and end-to-end validation are completed.
- The 10-per-user and 50-global daily limits do **not** apply while the current default direct-client path is active.
- The allowlisted command layer controls Media3 playback (play, pause, next, previous, shuffle on/off, repeat one/all/off), the existing sleep timer (open/cancel/5/10/15/20/30/45/60/90-minute presets), and supported in-app sections (Library, YouTube, Radio, queue, player, TV, Drive, Settings).
- Voice recognition immediately submits only a locally recognized allowlisted command. Other recognized speech remains editable in the prompt field and is not sent until the user taps Send.
- CI success proves the checked source compiles/tests under the workflow; it does not prove a real Gemini response, Vietnamese TTS playback, speech recognition accuracy, or command execution on the user's phone.
- Do not merge this branch, build a release APK, enable production App Check enforcement, or enable billing as part of this verification step.

## Next verification gate — no APK build

1. Confirm in Firebase Console that the Android app registered for package `com.ngocsi.music` belongs to project `ngoc-si-music-ai`.
2. Confirm the Gemini Developer API / Firebase AI Logic setup and review its current free-tier quotas and data-use terms.
3. For debug testing, obtain the App Check debug token from a debug app run and register it in Firebase App Check. Never post the token publicly. A successful compile alone cannot replace this step.
4. Only after the debug token is registered, test one ordinary Vietnamese question on a debug-installed app and record the exact error text if it fails. Do not include private data in test prompts.
5. The Android callable client is now present but remains disabled by default. Before enabling `NGOC_SI_AI_BACKEND_ENABLED=true`, deploy `ngocSiAiChat`, configure Anonymous Authentication, App Check, Firestore and Secret Manager, then run a real debug end-to-end test. Cloud Functions may require Firebase Blaze billing; review expected costs and set alerts/spend controls first. Do not claim server quotas protect the default direct-client path.

