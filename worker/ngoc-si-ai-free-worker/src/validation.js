export const MAX_MESSAGES = 8;
export const MAX_MESSAGE_CHARS = 2000;
export const MAX_TOTAL_CHARS = 8000;

export function validateMessages(data) {
  if (!data || typeof data !== "object" || !Array.isArray(data.messages)) {
    throw new Error("INVALID_INPUT");
  }
  const messages = data.messages;
  if (messages.length < 1 || messages.length > MAX_MESSAGES) {
    throw new Error("INVALID_MESSAGE_COUNT");
  }
  let total = 0;
  const normalized = messages.map((message) => {
    if (!message || typeof message !== "object") throw new Error("INVALID_MESSAGE");
    if (message.role !== "user" && message.role !== "assistant") {
      throw new Error("INVALID_ROLE");
    }
    if (typeof message.content !== "string") throw new Error("INVALID_CONTENT");
    const content = message.content.trim();
    if (!content || content.length > MAX_MESSAGE_CHARS) throw new Error("INVALID_CONTENT_LENGTH");
    total += content.length;
    return { role: message.role, content };
  });
  if (total > MAX_TOTAL_CHARS) throw new Error("INVALID_TOTAL_LENGTH");
  if (normalized[normalized.length - 1].role !== "user") throw new Error("LAST_MESSAGE_MUST_BE_USER");
  return normalized;
}

export function toGeminiContents(messages) {
  return messages.map(({ role, content }) => ({
    role: role === "assistant" ? "model" : "user",
    parts: [{ text: content }]
  }));
}

export function extractGeminiAnswer(payload) {
  const answer = payload?.candidates?.[0]?.content?.parts
    ?.map((part) => typeof part?.text === "string" ? part.text : "")
    .join("")
    .trim();
  if (!answer) throw new Error("EMPTY_MODEL_RESPONSE");
  return answer.slice(0, 12000);
}
