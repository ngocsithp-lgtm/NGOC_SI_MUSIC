# NGỌC SĨ AI — online free-tier setup (phone-friendly)

## Goal and cost boundary

This Worker keeps the Gemini API key on the server and lets the Android app call an online Vietnamese chatbot. It uses Cloudflare Workers Free plus the Gemini Developer API free tier where available. Neither provider guarantees unlimited usage or permanent free access. Quotas/models/terms can change. The Gemini free tier may use submitted content to improve Google products; do not send passwords, private documents, financial information, or other sensitive content. See:
- Cloudflare Workers limits: https://developers.cloudflare.com/workers/platform/limits/
- Cloudflare Worker secrets: https://developers.cloudflare.com/workers/configuration/secrets/
- Gemini API pricing and data-use notes: https://ai.google.dev/gemini-api/docs/pricing

This is an optional backend for the experimental branch only. It does not change the production `main` branch. The endpoint applies best-effort edge rate limits (5 requests/minute per IP and 10/minute service-wide per Cloudflare location), input limits, and a 512-token output cap. Cloudflare documents these rate-limit counters as per-location and eventually consistent; they are not a hard financial cap. The Gemini free-tier quota is an additional guard. Do not expose this endpoint as a guaranteed unlimited public service.

## One-time setup using an Android phone

Use Chrome. If the desktop console is hard to use, open the ⋮ menu and turn on **Desktop site**. Never send API keys, passwords, or Cloudflare tokens in ChatGPT messages or commit them to the repository.

### A. Create the Gemini key

1. Open https://aistudio.google.com/ and sign in.
2. Create an API key for the Google project you want to use.
3. Review the current model availability, free-tier limits, and data-use terms.
4. Copy the key temporarily into a secure password manager/notes area. Do not paste it into GitHub source code or into this chat.

### B. Create a Cloudflare Worker

1. Open https://dash.cloudflare.com/ and create/sign in to your Cloudflare account. Choose the Free plan; do not enable paid add-ons.
2. Open **Workers & Pages** and note the account ID from the account overview.
3. Create an API token with the minimum Workers Scripts edit/deploy permission for this account. Do not use a Global API Key.
4. In GitHub, open this repository → **Settings → Secrets and variables → Actions → New repository secret**. Add:
   - `CLOUDFLARE_API_TOKEN` = the Cloudflare API token
   - `CLOUDFLARE_ACCOUNT_ID` = the Cloudflare account ID
5. On the same page, choose **Variables** (not Secrets) → **New repository variable**:
   - Name: `NGOC_SI_AI_WORKER_URL`
   - Value: leave it blank until deployment gives you the workers.dev URL.

### C. Deploy the Worker from GitHub Actions

1. Open the repository's **Actions** tab.
2. Select **Deploy NGOC SI AI Free Worker**.
3. Tap **Run workflow** and choose branch `feature/ngoc-si-ai-android-integration`.
4. Wait for the run to succeed. The deployment log/summary shows the Worker URL, normally `https://ngoc-si-ai-free-worker.<your-subdomain>.workers.dev`.
5. Open Cloudflare → **Workers & Pages** → `ngoc-si-ai-free-worker` → **Settings → Variables and Secrets**. Add a secret named exactly `GEMINI_API_KEY`, paste the Gemini key, and save. Never add the key as a plain variable or to the Android app.
6. Test the Worker URL by opening `<worker-url>/health`. It should return JSON with `"ok":true`. A chat request will not work until `GEMINI_API_KEY` is saved.

### D. Connect the Android preview build

1. Go back to GitHub → repository **Settings → Secrets and variables → Actions → Variables**.
2. Set `NGOC_SI_AI_WORKER_URL` to the base URL from step C, for example `https://ngoc-si-ai-free-worker.<your-subdomain>.workers.dev`. Do not append `/v1/chat`; the Android app adds that path itself.
3. Open Actions → **NGOC SI AI Android Integration CI** → **Run workflow** on branch `feature/ngoc-si-ai-android-integration`.
4. Download the artifact `NGOC_SI_AI_ANDROID_INTEGRATION_PREVIEW_DEBUG_APK` from the successful run. This new build will use the Worker URL.
5. Install the preview APK and test a short text question, then voice input and spoken response. The Worker does not execute Android music actions; allowed local commands remain local.

## Troubleshooting

- HTTP 429: the per-IP/service rate limiter or Gemini free-tier quota was reached; wait and retry later.
- HTTP 503 / “server not configured”: add the `GEMINI_API_KEY` secret in Cloudflare Worker settings.
- Android still says online AI is not configured: check the GitHub Actions repository variable name/value, then build a new preview APK.
- Cloudflare deploy fails: verify account ID, API token scope, and that the selected workflow branch is the experimental branch.
- Do not switch the app to a paid plan or add billing to “fix” a free-tier limit without reviewing the cost first.

## What is and is not automatic

The repository now contains Worker source, tests, a manual deploy workflow, and Android client support. A real online service cannot be activated until an owner-controlled Cloudflare account and Gemini API key are configured. This setup is deliberately not automated with guessed credentials. No secret key is required in the APK.
