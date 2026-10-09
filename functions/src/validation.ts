export type ChatRole = "user" | "assistant";
export type ChatMessage = { role: ChatRole; content: string };

export const MAX_MESSAGE_CHARS = 2000;
export const MAX_HISTORY_MESSAGES = 8;
export const MAX_TOTAL_CHARS = 8000;

export function validateChatInput(value: unknown): ChatMessage[] {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("INVALID_INPUT");
  const raw = (value as { messages?: unknown }).messages;
  if (!Array.isArray(raw) || raw.length < 1 || raw.length > MAX_HISTORY_MESSAGES) throw new Error("INVALID_HISTORY");
  const messages: ChatMessage[] = [];
  let totalChars = 0;
  for (const item of raw) {
    if (!item || typeof item !== "object" || Array.isArray(item)) throw new Error("INVALID_MESSAGE");
    const role = (item as { role?: unknown }).role;
    const content = (item as { content?: unknown }).content;
    if (role !== "user" && role !== "assistant") throw new Error("INVALID_ROLE");
    if (typeof content !== "string") throw new Error("INVALID_CONTENT");
    const normalized = content.trim();
    if (!normalized || normalized.length > MAX_MESSAGE_CHARS) throw new Error("INVALID_LENGTH");
    totalChars += normalized.length;
    if (totalChars > MAX_TOTAL_CHARS) throw new Error("TOTAL_LENGTH_EXCEEDED");
    messages.push({ role, content: normalized });
  }
  if (messages[messages.length - 1]?.role !== "user") throw new Error("LAST_MESSAGE_MUST_BE_USER");
  return messages;
}

export function dailyQuotaDocumentId(uid: string, date: string): string {
  const safeUid = uid.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 100);
  return `${safeUid}_${date}`;
}

export function toGeminiContents(messages: ChatMessage[]): Array<{
  role: "user" | "model";
  parts: Array<{ text: string }>;
}> {
  return messages.map((message) => ({
    role: message.role === "assistant" ? "model" : "user",
    parts: [{ text: message.content }]
  }));
}

export function extractGeminiAnswer(value: unknown): string {
  if (!value || typeof value !== "object" || Array.isArray(value)) return "";
  const candidates = (value as { candidates?: unknown }).candidates;
  if (!Array.isArray(candidates) || candidates.length === 0) return "";
  const first = candidates[0];
  if (!first || typeof first !== "object") return "";
  const content = (first as { content?: unknown }).content;
  if (!content || typeof content !== "object") return "";
  const parts = (content as { parts?: unknown }).parts;
  if (!Array.isArray(parts)) return "";
  return parts
    .filter((part): part is { text: string } => !!part && typeof part === "object" && typeof (part as { text?: unknown }).text === "string")
    .map((part) => part.text)
    .join("")
    .trim();
}
