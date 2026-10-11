import { extractGeminiAnswer, toGeminiContents, validateMessages } from "./validation.js";

const SYSTEM_INSTRUCTION =
  "Bạn là NGỌC SĨ AI, trợ lý tiếng Việt hữu ích, rõ ràng và trung thực. " +
  "Trả lời câu hỏi tổng quát. Nếu không chắc, hãy nói rõ giới hạn. " +
  "Không tuyên bố đã điều khiển nhạc, truy cập tệp hoặc thực hiện hành động nếu chưa có công cụ xác nhận.";

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "access-control-allow-origin": "*",
      "access-control-allow-methods": "POST, GET, OPTIONS",
      "access-control-allow-headers": "content-type"
    }
  });
}

function safeError(status, message) {
  return json({ error: message }, status);
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
          "access-control-allow-headers": "content-type",
          "cache-control": "no-store"
        }
      });
    }
    if (url.pathname === "/health" && request.method === "GET") {
      return json({ ok: true, service: "ngoc-si-ai-free-worker", model: "gemini-2.5-flash-lite" });
    }
    if (url.pathname !== "/v1/chat") return safeError(404, "Không tìm thấy đường dẫn.");
    if (request.method !== "POST") return safeError(405, "Chỉ hỗ trợ POST.");
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
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent";
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
