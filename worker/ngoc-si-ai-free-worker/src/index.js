import { extractGeminiAnswer, toGeminiContents, validateMessages } from "./validation.js";

const MODEL_ID = "gemini-3.5-flash-lite";

const SYSTEM_INSTRUCTION =
  "Bạn là NGỌC SĨ AI, trợ lý tiếng Việt hữu ích, rõ ràng và trung thực. " +
  "Trả lời câu hỏi tổng quát. Nếu không chắc, hãy nói rõ giới hạn. " +
  "Không tuyên bố đã điều khiển nhạc, truy cập tệp hoặc thực hiện hành động nếu chưa có công cụ xác nhận.";

const JWKS_CACHE_TTL_MS = 60 * 60 * 1000;
const jwksCache = new Map();

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "access-control-allow-origin": "*",
      "access-control-allow-methods": "POST, GET, OPTIONS",
      "access-control-allow-headers": "content-type, authorization, x-firebase-appcheck"
    }
  });
}

function safeError(status, message) {
  return json({ error: message }, status);
}

function decodeBase64Url(segment) {
  const normalized = segment.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, char => char.charCodeAt(0));
}

function decodeJwt(token) {
  if (typeof token !== "string" || token.length > 12000) throw new Error("invalid token");
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("invalid token");
  const header = JSON.parse(new TextDecoder().decode(decodeBase64Url(parts[0])));
  const payload = JSON.parse(new TextDecoder().decode(decodeBase64Url(parts[1])));
  return { header, payload, signingInput: new TextEncoder().encode(parts[0] + "." + parts[1]), signature: decodeBase64Url(parts[2]) };
}

async function loadJwks(url) {
  const cached = jwksCache.get(url);
  if (cached && cached.expiresAt > Date.now()) return cached.keys;
  const response = await fetch(url, { headers: { accept: "application/json" }, signal: AbortSignal.timeout(5000) });
  if (!response.ok) throw new Error("key service unavailable");
  const data = await response.json();
  if (!Array.isArray(data.keys) || data.keys.length === 0) throw new Error("invalid key set");
  jwksCache.set(url, { keys: data.keys, expiresAt: Date.now() + JWKS_CACHE_TTL_MS });
  return data.keys;
}

async function verifyJwtSignature(token, jwksUrl, expectedAlgorithm, importAlgorithm, verifyAlgorithm) {
  const decoded = decodeJwt(token);
  if (decoded.header.alg !== expectedAlgorithm || typeof decoded.header.kid !== "string") throw new Error("invalid JWT header");
  const keys = await loadJwks(jwksUrl);
  const jwk = keys.find(key => key.kid === decoded.header.kid && (!key.alg || key.alg === expectedAlgorithm));
  if (!jwk) {
    jwksCache.delete(jwksUrl);
    const refreshed = await loadJwks(jwksUrl);
    const freshKey = refreshed.find(key => key.kid === decoded.header.kid && (!key.alg || key.alg === expectedAlgorithm));
    if (!freshKey) throw new Error("unknown signing key");
    return { decoded, key: freshKey, importAlgorithm, verifyAlgorithm };
  }
  return { decoded, key: jwk, importAlgorithm, verifyAlgorithm };
}

function checkTimeClaims(payload) {
  const now = Math.floor(Date.now() / 1000);
  return Number.isFinite(payload.exp) && payload.exp > now &&
    Number.isFinite(payload.iat) && payload.iat <= now + 60 &&
    (!Number.isFinite(payload.nbf) || payload.nbf <= now + 60);
}

async function verifyFirebaseIdToken(token, env) {
  const projectId = env.FIREBASE_PROJECT_ID;
  if (!projectId) throw new Error("Firebase project is not configured");
  const jwksUrl = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
  const verified = await verifyJwtSignature(token, jwksUrl, "RS256", { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, { name: "RSASSA-PKCS1-v1_5" });
  const { decoded, key } = verified;
  const payload = decoded.payload;
  if (key.kty !== "RSA" || payload.aud !== projectId ||
      payload.iss !== "https://securetoken.google.com/" + projectId ||
      typeof payload.sub !== "string" || payload.sub.length === 0 || payload.sub.length > 128 ||
      !checkTimeClaims(payload)) throw new Error("invalid Firebase ID token claims");
  const cryptoKey = await crypto.subtle.importKey("jwk", key, verified.importAlgorithm, false, ["verify"]);
  const valid = await crypto.subtle.verify(verified.verifyAlgorithm, cryptoKey, decoded.signature, decoded.signingInput);
  if (!valid) throw new Error("invalid Firebase ID token signature");
  return payload.sub;
}

async function verifyAppCheckToken(token, env) {
  const projectNumber = env.FIREBASE_PROJECT_NUMBER;
  const expectedAppId = env.FIREBASE_APP_ID;
  if (!projectNumber || !expectedAppId) throw new Error("Firebase App Check is not configured");
  const jwksUrl = "https://firebaseappcheck.googleapis.com/v1/jwks";
  const verified = await verifyJwtSignature(token, jwksUrl, "ES256", { name: "ECDSA", namedCurve: "P-256" }, { name: "ECDSA", hash: "SHA-256" });
  const { decoded, key } = verified;
  const payload = decoded.payload;
  const audiences = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  if (key.kty !== "EC" || key.crv !== "P-256" ||
      payload.iss !== "https://firebaseappcheck.googleapis.com/" + projectNumber ||
      !audiences.includes("projects/" + projectNumber) ||
      payload.sub !== expectedAppId || !checkTimeClaims(payload)) {
    throw new Error("invalid Firebase App Check claims");
  }
  const cryptoKey = await crypto.subtle.importKey("jwk", key, verified.importAlgorithm, false, ["verify"]);
  const valid = await crypto.subtle.verify(verified.verifyAlgorithm, cryptoKey, decoded.signature, decoded.signingInput);
  if (!valid) throw new Error("invalid Firebase App Check signature");
}

async function authenticateRequest(request, env) {
  const authorization = request.headers.get("Authorization") || "";
  const appCheck = request.headers.get("X-Firebase-AppCheck") || "";
  if (!authorization.startsWith("Bearer ") || !appCheck) return null;
  try {
    const uid = await verifyFirebaseIdToken(authorization.slice(7).trim(), env);
    await verifyAppCheckToken(appCheck, env);
    return uid;
  } catch {
    return null;
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "access-control-allow-origin": "*",
          "access-control-allow-methods": "POST, GET, OPTIONS",
          "access-control-allow-headers": "content-type, authorization, x-firebase-appcheck",
          "cache-control": "no-store"
        }
      });
    }
    if (url.pathname === "/health" && request.method === "GET") {
      return json({ ok: true, service: "ngoc-si-ai-free-worker", model: MODEL_ID });
    }
    if (url.pathname !== "/v1/chat") return safeError(404, "Không tìm thấy đường dẫn.");
    if (request.method !== "POST") return safeError(405, "Chỉ hỗ trợ POST.");
    const uid = await authenticateRequest(request, env);
    if (!uid) return safeError(401, "Yêu cầu chưa được xác thực. Hãy cập nhật ứng dụng và thử lại.");
    if (!env.GEMINI_API_KEY) return safeError(503, "Máy chủ chưa được cấu hình khóa AI.");

    // Best-effort edge throttling. Cloudflare documents these counters as per-location
    // and eventually consistent, so provider free-tier quotas remain an additional guard.
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    if (env.PER_IP_LIMIT) {
      const result = await env.PER_IP_LIMIT.limit({ key: ip });
      if (!result.success) return safeError(429, "Bạn gửi quá nhanh. Vui lòng đợi một phút rồi thử lại.");
    }
    if (env.GLOBAL_LIMIT) {
      const result = await env.GLOBAL_LIMIT.limit({ key: "all-ai-requests" });
      if (!result.success) return safeError(429, "AI đang chạm giới hạn tạm thời. Vui lòng thử lại sau.");
    }

    let data;
    try {
      const length = Number(request.headers.get("content-length") || 0);
      if (length > 24000) return safeError(413, "Yêu cầu quá dài. Hãy rút gọn câu hỏi.");
      const rawBody = await request.text();
      if (rawBody.length > 24000) return safeError(413, "Yêu cầu quá dài. Hãy rút gọn câu hỏi.");
      data = JSON.parse(rawBody);
    } catch {
      return safeError(400, "Dữ liệu gửi lên không hợp lệ.");
    }

    let messages;
    try {
      messages = validateMessages(data);
    } catch {
      return safeError(400, "Tin nhắn không hợp lệ hoặc quá dài. Hãy gửi câu hỏi ngắn hơn.");
    }

    const upstreamUrl =
      `https://generativelanguage.googleapis.com/v1beta/models/${MODEL_ID}:generateContent`;
    let upstream;
    try {
      upstream = await fetch(upstreamUrl + "?key=" + encodeURIComponent(env.GEMINI_API_KEY), {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          systemInstruction: { parts: [{ text: SYSTEM_INSTRUCTION }] },
          contents: toGeminiContents(messages),
          generationConfig: { maxOutputTokens: 512, temperature: 0.6 }
        }),
        signal: AbortSignal.timeout(25000)
      });
    } catch {
      return safeError(502, "Không kết nối được dịch vụ AI. Hãy thử lại sau.");
    }

    if (!upstream.ok) {
      if (upstream.status === 429) return safeError(429, "Đã chạm hạn mức miễn phí của AI. Vui lòng thử lại sau.");
      if (upstream.status === 400 || upstream.status === 404) {
        return safeError(502, "Dịch vụ AI chưa sẵn sàng cho cấu hình hiện tại.");
      }
      return safeError(502, "Dịch vụ AI tạm thời gặp lỗi. Hãy thử lại sau.");
    }

    let payload;
    try {
      payload = await upstream.json();
      return json({ answer: extractGeminiAnswer(payload), remainingToday: null, remainingGlobalToday: null });
    } catch {
      return safeError(502, "AI chưa tạo được câu trả lời. Hãy thử lại.");
    }
  }
};
