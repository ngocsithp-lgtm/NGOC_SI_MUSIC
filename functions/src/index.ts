import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { defineSecret } from "firebase-functions/params";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import {
  dailyQuotaDocumentId,
  extractGeminiAnswer,
  toGeminiContents,
  validateChatInput
} from "./validation.js";

initializeApp();
const db = getFirestore();
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
// Keep a conservative app-side limit even when the provider has a free tier.
const DAILY_LIMIT = 10;
// A server-wide daily ceiling limits provider usage even if many UIDs are created.
const GLOBAL_DAILY_LIMIT = 50;
const MODEL = "gemini-3.5-flash-lite";
const SYSTEM_INSTRUCTIONS =
  "Bạn là NGỌC SĨ AI, trợ lý tiếng Việt hữu ích, rõ ràng và trung thực. " +
  "Trả lời câu hỏi tổng quát. Nếu không chắc, hãy nói rõ giới hạn. " +
  "Không tuyên bố đã điều khiển nhạc, truy cập tệp, hoặc thực hiện hành động nếu chưa có công cụ xác nhận.";

export const ngocSiAiChat = onCall(
  {
    region: "asia-southeast1",
    enforceAppCheck: true,
    secrets: [GEMINI_API_KEY],
    timeoutSeconds: 45,
    memory: "256MiB"
  },
  async (request) => {
    if (!request.auth?.uid) {
      throw new HttpsError("unauthenticated", "Vui lòng đăng nhập để dùng NGỌC SĨ AI.");
    }
    let messages;
    try {
      messages = validateChatInput(request.data);
    } catch (error) {
      const reason = error instanceof Error ? error.message : "INVALID_INPUT";
      throw new HttpsError("invalid-argument", reason);
    }

    const apiKey = GEMINI_API_KEY.value();
    if (!apiKey) {
      throw new HttpsError("failed-precondition", "Máy chủ AI chưa được cấu hình khóa Gemini.");
    }

    // Reserve per-user and global quotas atomically to cap provider requests per UTC day.
    // Store only UID/date/count metadata, never conversation text.
    const date = new Date().toISOString().slice(0, 10);
    const uid = request.auth.uid;
    const quotaRef = db.collection("aiDailyUsage").doc(dailyQuotaDocumentId(uid, date));
    const globalRef = db.collection("aiGlobalUsage").doc(date);
    let usedToday: number;
    let globalUsedToday: number;
    try {
      const reserved = await db.runTransaction(async (transaction) => {
        const [snapshot, globalSnapshot] = await Promise.all([
          transaction.get(quotaRef),
          transaction.get(globalRef)
        ]);
        const used = Number(snapshot.get("count") ?? 0);
        const globalUsed = Number(globalSnapshot.get("count") ?? 0);
        if (!Number.isSafeInteger(used) || used < 0 ||
            !Number.isSafeInteger(globalUsed) || globalUsed < 0) {
          throw new HttpsError("internal", "Hạn mức AI không hợp lệ.");
        }
        if (globalUsed >= GLOBAL_DAILY_LIMIT) {
          throw new HttpsError("resource-exhausted", "NGỌC SĨ AI đã đạt giới hạn tổng lượt hôm nay. Vui lòng thử lại ngày mai.");
        }
        if (used >= DAILY_LIMIT) {
          throw new HttpsError("resource-exhausted", "Bạn đã dùng hết 10 lượt AI hôm nay.");
        }
        const nextCount = used + 1;
        const nextGlobalCount = globalUsed + 1;
        transaction.set(quotaRef, {
          uid,
          date,
          count: nextCount,
          updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
        transaction.set(globalRef, {
          date,
          count: nextGlobalCount,
          updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
        return { usedToday: nextCount, globalUsedToday: nextGlobalCount };
      });
      usedToday = reserved.usedToday;
      globalUsedToday = reserved.globalUsedToday;
    } catch (error) {
      if (error instanceof HttpsError) throw error;
      throw new HttpsError("unavailable", "Không thể kiểm tra hạn mức AI. Vui lòng thử lại.");
    }

    let upstream: Response;
    try {
      upstream = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`,
        {
          method: "POST",
          headers: {
            "x-goog-api-key": apiKey,
            "Content-Type": "application/json"
          },
          body: JSON.stringify({
            systemInstruction: { parts: [{ text: SYSTEM_INSTRUCTIONS }] },
            contents: toGeminiContents(messages),
            generationConfig: {
              temperature: 0.4,
              maxOutputTokens: 600
            }
          }),
          signal: AbortSignal.timeout(35000)
        }
      );
    } catch {
      throw new HttpsError("unavailable", "Máy chủ AI tạm thời không kết nối được.");
    }

    if (!upstream.ok) {
      if (upstream.status === 429) {
        throw new HttpsError("resource-exhausted", "Gemini đã chạm hạn mức miễn phí hoặc giới hạn tốc độ. Vui lòng thử lại sau.");
      }
      if (upstream.status >= 500) {
        throw new HttpsError("unavailable", "Nhà cung cấp AI đang gặp sự cố.");
      }
      throw new HttpsError("failed-precondition", "Gemini chưa chấp nhận yêu cầu. Hãy kiểm tra cấu hình máy chủ.");
    }

    let answer = "";
    try {
      answer = extractGeminiAnswer(await upstream.json());
    } catch {
      answer = "";
    }
    if (!answer) {
      throw new HttpsError("unavailable", "AI chưa trả lời được. Vui lòng thử lại.");
    }
    return {
    answer,
    remainingToday: Math.max(0, DAILY_LIMIT - usedToday),
    remainingGlobalToday: Math.max(0, GLOBAL_DAILY_LIMIT - globalUsedToday)
  };
  }
);
