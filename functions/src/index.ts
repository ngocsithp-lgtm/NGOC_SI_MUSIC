import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { defineSecret } from "firebase-functions/params";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { dailyQuotaDocumentId, validateChatInput } from "./validation.js";

initializeApp();
const db = getFirestore();
const OPENAI_API_KEY = defineSecret("OPENAI_API_KEY");
// Low-cost starter limits. Raise only after reviewing actual usage and billing.
const DAILY_LIMIT = 10;
const MODEL = "gpt-4o-mini";
const SYSTEM_INSTRUCTIONS =
  "Bạn là NGỌC SĨ AI, trợ lý tiếng Việt hữu ích, rõ ràng và trung thực. " +
  "Trả lời câu hỏi tổng quát như một trợ lý AI. Nếu không chắc, hãy nói rõ giới hạn. " +
  "Không tuyên bố đã điều khiển nhạc, truy cập tệp, hoặc thực hiện hành động nếu chưa có công cụ xác nhận.";

export const ngocSiAiChat = onCall(
  {
    region: "asia-southeast1",
    enforceAppCheck: true,
    secrets: [OPENAI_API_KEY],
    timeoutSeconds: 45,
    memory: "256MiB"
  },
  async (request) => {
    if (!request.auth?.uid) throw new HttpsError("unauthenticated", "Vui lòng đăng nhập để dùng NGỌC SĨ AI.");
    let messages;
    try {
      messages = validateChatInput(request.data);
    } catch (error) {
      const reason = error instanceof Error ? error.message : "INVALID_INPUT";
      throw new HttpsError("invalid-argument", reason);
    }

    // Use a server-side transaction so concurrent requests cannot exceed the daily limit.
    // The quota date is UTC; no message content is stored.
    const date = new Date().toISOString().slice(0, 10);
    const uid = request.auth.uid;
    const quotaRef = db.collection("aiDailyUsage").doc(dailyQuotaDocumentId(uid, date));
    let usedToday: number;
    try {
      usedToday = await db.runTransaction(async (transaction) => {
        const snapshot = await transaction.get(quotaRef);
        const used = Number(snapshot.get("count") ?? 0);
        if (!Number.isSafeInteger(used) || used < 0) {
          throw new HttpsError("internal", "Hạn mức AI không hợp lệ.");
        }
        if (used >= DAILY_LIMIT) {
          throw new HttpsError("resource-exhausted", "Bạn đã dùng hết 10 lượt AI hôm nay.");
        }
        const nextCount = used + 1;
        transaction.set(quotaRef, {
          uid,
          date,
          count: nextCount,
          updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
        return nextCount;
      });
    } catch (error) {
      if (error instanceof HttpsError) throw error;
      throw new HttpsError("unavailable", "Không thể kiểm tra hạn mức AI. Vui lòng thử lại.");
    }

    const apiKey = OPENAI_API_KEY.value();
    if (!apiKey) throw new HttpsError("failed-precondition", "Máy chủ AI chưa được cấu hình khóa API.");
    let upstream: Response;
    try {
      upstream = await fetch("https://api.openai.com/v1/chat/completions", {
        method: "POST",
        headers: { "Authorization": `Bearer ${apiKey}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: MODEL,
          temperature: 0.4,
          max_tokens: 600,
          messages: [{ role: "system", content: SYSTEM_INSTRUCTIONS }, ...messages]
        }),
        signal: AbortSignal.timeout(35000)
      });
    } catch {
      throw new HttpsError("unavailable", "Máy chủ AI tạm thời không kết nối được.");
    }

    if (!upstream.ok) {
      if (upstream.status === 429) throw new HttpsError("resource-exhausted", "Dịch vụ AI đang giới hạn lượt gọi. Vui lòng thử lại sau.");
      if (upstream.status >= 500) throw new HttpsError("unavailable", "Nhà cung cấp AI đang gặp sự cố.");
      throw new HttpsError("failed-precondition", "Yêu cầu AI chưa được nhà cung cấp chấp nhận.");
    }

    let answer = "";
    try {
      const payload = await upstream.json() as { choices?: Array<{ message?: { content?: unknown } }> };
      const content = payload.choices?.[0]?.message?.content;
      if (typeof content === "string") answer = content.trim();
    } catch {}
    if (!answer) throw new HttpsError("unavailable", "AI chưa trả lời được. Vui lòng thử lại.");
    return { answer, remainingToday: Math.max(0, DAILY_LIMIT - usedToday) };
  }
);
