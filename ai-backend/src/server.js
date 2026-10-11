import express from "express";
import { rateLimit } from "express-rate-limit";
import { GoogleGenAI } from "@google/genai";
import { applicationDefault, getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";

const app = express();
const port = Number(process.env.PORT || 8080);
const maxMessageChars = 2000;

app.disable("x-powered-by");
app.use(express.json({ limit: "8kb", strict: true }));

const chatLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 20,
  standardHeaders: "draft-8",
  legacyHeaders: false,
  message: { error: "Bạn gửi yêu cầu quá nhanh. Vui lòng thử lại sau." }
});

app.get("/healthz", (_req, res) => {
  res.status(200).json({ status: "ok", deployed: false });
});

function requireRuntimeConfig() {
  if (!process.env.GEMINI_API_KEY) {
    throw new Error("Missing GEMINI_API_KEY runtime secret");
  }
  if (getApps().length === 0) {
    initializeApp({ credential: applicationDefault() });
  }
}

async function requireFirebaseUser(req, res, next) {
  const header = req.get("authorization") || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  if (!match) {
    return res.status(401).json({ error: "Cần đăng nhập để sử dụng NGỌC SĨ AI." });
  }

  try {
    const decoded = await getAuth().verifyIdToken(match[1], true);
    req.authUser = { uid: decoded.uid };
    return next();
  } catch {
    return res.status(401).json({ error: "Phiên đăng nhập không hợp lệ hoặc đã hết hạn." });
  }
}

app.post("/v1/chat", chatLimiter, requireFirebaseUser, async (req, res) => {
  const message = req.body?.message;
  if (typeof message !== "string" || message.trim().length === 0) {
    return res.status(400).json({ error: "Vui lòng nhập nội dung cần hỏi." });
  }
  if (message.length > maxMessageChars) {
    return res.status(413).json({ error: "Nội dung quá dài (tối đa 2000 ký tự)." });
  }

  try {
    requireRuntimeConfig();
    const ai = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });
    const response = await ai.models.generateContent({
      model: process.env.GEMINI_MODEL || "gemini-2.5-flash",
      contents: [
        "Bạn là NGỌC SĨ AI, trợ lý tiếng Việt cho ứng dụng nghe nhạc. Trả lời rõ ràng, ngắn gọn. Không tuyên bố đã thực hiện thao tác trên điện thoại nếu ứng dụng chưa xác nhận. Chỉ đề xuất hành động ứng dụng; không tự thực thi lệnh nguy hiểm.",
        `Yêu cầu người dùng: ${message.trim()}`
      ]
    });

    const answer = typeof response.text === "string" ? response.text.trim() : "";
    if (!answer) {
      return res.status(502).json({ error: "AI chưa trả về câu trả lời. Vui lòng thử lại." });
    }
    return res.status(200).json({ answer });
  } catch (error) {
    // Do not log prompts, tokens, API keys, or provider error bodies.
    console.error("NGOC_SI_AI_REQUEST_FAILED", error?.name || "Error");
    return res.status(503).json({ error: "NGỌC SĨ AI tạm thời chưa sẵn sàng." });
  }
});

app.use((error, _req, res, _next) => {
  if (error?.type === "entity.too.large") {
    return res.status(413).json({ error: "Yêu cầu quá lớn." });
  }
  return res.status(400).json({ error: "Dữ liệu yêu cầu không hợp lệ." });
});

app.listen(port, () => {
  console.log(`NGỌC SĨ AI scaffold listening on port ${port}; deployment is not configured here.`);
});
