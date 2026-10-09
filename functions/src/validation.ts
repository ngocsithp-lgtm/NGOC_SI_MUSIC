export type ChatRole = "user" | "assistant";
export type ChatMessage = { role: ChatRole; content: string };

export const MAX_MESSAGE_CHARS = 4000;
export const MAX_HISTORY_MESSAGES = 12;

export function validateChatInput(value: unknown): ChatMessage[] {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("INVALID_INPUT");
  const raw = (value as { messages?: unknown }).messages;
  if (!Array.isArray(raw) || raw.length < 1 || raw.length > MAX_HISTORY_MESSAGES) throw new Error("INVALID_HISTORY");
  const messages: ChatMessage[] = [];
  for (const item of raw) {
    if (!item || typeof item !== "object" || Array.isArray(item)) throw new Error("INVALID_MESSAGE");
    const role = (item as { role?: unknown }).role;
    const content = (item as { content?: unknown }).content;
    if (role !== "user" && role !== "assistant") throw new Error("INVALID_ROLE");
    if (typeof content !== "string") throw new Error("INVALID_CONTENT");
    const normalized = content.trim();
    if (!normalized || normalized.length > MAX_MESSAGE_CHARS) throw new Error("INVALID_LENGTH");
    messages.push({ role, content: normalized });
  }
  if (messages[messages.length - 1]?.role !== "user") throw new Error("LAST_MESSAGE_MUST_BE_USER");
  return messages;
}

export function dailyQuotaDocumentId(uid: string, date: string): string {
  const safeUid = uid.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 100);
  return `${safeUid}_${date}`;
}
