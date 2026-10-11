import test from "node:test";
import assert from "node:assert/strict";
import { extractGeminiAnswer, toGeminiContents, validateMessages } from "../src/validation.js";
import worker from "../src/index.js";

test("accepts a bounded user message", () => {
  assert.deepEqual(validateMessages({ messages: [{ role: "user", content: "Xin chào" }] }), [
    { role: "user", content: "Xin chào" }
  ]);
});

test("rejects unsupported roles and non-user final message", () => {
  assert.throws(() => validateMessages({ messages: [{ role: "system", content: "x" }] }));
  assert.throws(() => validateMessages({ messages: [{ role: "user", content: "x" }, { role: "assistant", content: "y" }] }));
});

test("rejects excessive message count and total length", () => {
  assert.throws(() => validateMessages({ messages: Array.from({ length: 9 }, () => ({ role: "user", content: "x" })) }));
  assert.throws(() => validateMessages({ messages: [{ role: "user", content: "x".repeat(2001) }] }));
  assert.throws(() => validateMessages({ messages: [{ role: "user", content: "x".repeat(2000) }, { role: "assistant", content: "x".repeat(2000) }, { role: "user", content: "x".repeat(2000) }, { role: "assistant", content: "x".repeat(2000) }, { role: "user", content: "x".repeat(1) }] }));
});

test("maps assistant history to Gemini model role", () => {
  assert.deepEqual(toGeminiContents([
    { role: "user", content: "A" }, { role: "assistant", content: "B" }
  ]), [
    { role: "user", parts: [{ text: "A" }] }, { role: "model", parts: [{ text: "B" }] }
  ]);
});

test("extracts response text and rejects empty candidates", () => {
  assert.equal(extractGeminiAnswer({ candidates: [{ content: { parts: [{ text: " Xin chào " }] } }] }), "Xin chào");
  assert.throws(() => extractGeminiAnswer({ candidates: [] }));
});

test("health endpoint reports the same model selected for chat", async () => {
  const response = await worker.fetch(new Request("https://example.test/health"), {});
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    ok: true,
    service: "ngoc-si-ai-free-worker",
    model: "gemini-3.5-flash-lite"
  });
});
