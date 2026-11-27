import { expect, it, vi } from "vitest";
import { LlmSelection, type LlmPort } from "./llmSelection";
import type { LlmEndpoint } from "../../entities/llm";
export const endpoint = (id = "local"): LlmEndpoint => ({
  profile: { id, model: `${id}-model`, provider: "openai-compatible", api: "chat-completions", authentication: "none", accountRef: null, usagePath: "local", capabilities: { route: {}, server: {}, model: {}, effective: {} } },
  active: true, reasoningReplay: "NONE", authenticationState: "not-required", unavailableReason: null,
  generationSettings: {}, unappliedSettings: {}, diagnostics: [], selectionScope: "process-wide; next Run only",
});
const port = (): LlmPort => ({
  endpoints: vi.fn().mockResolvedValue([endpoint()]),
  select: vi.fn().mockImplementation(async (_server, id) => endpoint(id)),
  models: vi.fn().mockResolvedValue({ endpoint: "local", currentModel: "local-model", models: ["local-model", "second"] }),
  selectModel: vi.fn().mockResolvedValue({ endpoint: "local", currentModel: "second", models: [] }),
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => { resolve = r; });
  return { promise, resolve };
}
it("reads metadata for the conversation server without discovering models", async () => {
  const api = port(); const selection = new LlmSelection(api, "conversation-server");
  await selection.refresh();
  expect(api.endpoints).toHaveBeenCalledWith("conversation-server");
  expect(selection.snapshot().endpoints[0].profile.id).toBe("local");
  expect(api.models).not.toHaveBeenCalled();
});
it("binds a model write to the endpoint whose catalog was displayed", async () => {
  const api = port(); const selection = new LlmSelection(api, "server");
  await selection.refresh(); await selection.loadModels(); await selection.selectModel("second");
  expect(api.selectModel).toHaveBeenCalledWith("server", "local", "second");
  expect(selection.snapshot().models?.currentModel).toBe("second");
});
it("does not write a model before explicit discovery or outside the returned catalog", async () => {
  const api = port(); const selection = new LlmSelection(api, "server");
  await selection.refresh(); await selection.selectModel("second");
  expect(api.selectModel).not.toHaveBeenCalled();
  await selection.loadModels(); await selection.selectModel("unlisted");
  expect(api.selectModel).not.toHaveBeenCalled();
});
it("discards old model discovery after selecting another endpoint", async () => {
  const api = port(); const old = deferred<Awaited<ReturnType<LlmPort["models"]>>>();
  vi.mocked(api.models).mockReturnValueOnce(old.promise);
  const selection = new LlmSelection(api, "server"); await selection.refresh();
  const pending = selection.loadModels(); await selection.select("plan");
  old.resolve({ endpoint: "local", currentModel: "old", models: ["old"] }); await pending;
  expect(selection.snapshot().models).toBeNull();
  expect(selection.snapshot().endpoints.find((e) => e.active)?.profile.id).toBe("plan");
});
it("serializes writes and keeps sending blocked until mutation and refresh settle", async () => {
  const api = port(); const pending = deferred<LlmEndpoint>();
  vi.mocked(api.select).mockReturnValueOnce(pending.promise);
  const selection = new LlmSelection(api, "server"); await selection.refresh();
  const write = selection.select("plan");
  expect(selection.snapshot().busy).toBe(true);
  await selection.select("other"); expect(api.select).toHaveBeenCalledTimes(1);
  pending.resolve(endpoint("plan")); await write;
  expect(selection.snapshot().busy).toBe(false);
});
it("never displays raw transport errors and exposes unsupported older servers", async () => {
  const api = port(); vi.mocked(api.endpoints).mockRejectedValueOnce("EndpointNotFound").mockRejectedValueOnce(new Error("PRIVATE_TOKEN"));
  const selection = new LlmSelection(api, "server"); await selection.refresh();
  expect(selection.snapshot().error).toBe("EndpointNotFound");
  await selection.refresh(); expect(selection.snapshot().error).toBe("InvalidResponse");
  expect(JSON.stringify(selection.snapshot())).not.toContain("PRIVATE_TOKEN");
});
