import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { LlmControls } from "./LlmControls";
import type { LlmPort } from "./llmSelection";
import type { LlmEndpoint } from "../../entities/llm";
afterEach(cleanup);
const row = (id: string, active = true): LlmEndpoint => ({
  profile: { id, model: `${id}-model`, provider: "chatgpt-plan", api: "responses", authentication: "chatgpt-oauth", accountRef: "personal", usagePath: "chatgpt-plan", capabilities: { route: { VISION: "SUPPORTED" }, server: { VISION: "UNKNOWN" }, model: { VISION: "UNKNOWN" }, effective: { VISION: "UNKNOWN" } } },
  active, reasoningReplay: "ENCRYPTED", authenticationState: "unverified", unavailableReason: "ChatGPT account registration requires verification",
  generationSettings: { reasoning_effort: "high" }, unappliedSettings: { temperature: "unsupported" }, diagnostics: [], selectionScope: "process-wide; next Run only; resets on restart",
});
function api(): LlmPort {
  return { endpoints: vi.fn().mockResolvedValue([row("plan"), row("local", false)]), select: vi.fn().mockImplementation(async (_server, id) => row(id)), models: vi.fn().mockResolvedValue({ endpoint: "plan", currentModel: "plan-model", models: ["plan-model", "other"] }), selectModel: vi.fn().mockResolvedValue({ endpoint: "plan", currentModel: "other", models: [] }) };
}
it("shows declared usage, authentication, restrictions and scope without model discovery", async () => {
  const port = api(); render(<LlmControls serverId="conversation-server" port={port} />);
  await screen.findByRole("combobox", { name: "LLM接続先" });
  expect(port.endpoints).toHaveBeenCalledWith("conversation-server");
  expect(port.models).not.toHaveBeenCalled();
  expect(screen.getByText(/アカウント: personal/)).toBeTruthy();
  expect(screen.getByText(/ChatGPT account registration requires verification/)).toBeTruthy();
  expect(screen.getByText(/次のRun/)).toBeTruthy();
  fireEvent.click(screen.getByText("接続先の詳細"));
  expect(screen.getByText(/reasoning_effort/)).toBeTruthy();
  expect(screen.getByText(/temperature/)).toBeTruthy();
  expect(screen.getAllByText(/UNKNOWN/).length).toBeGreaterThan(0);
});
it("uses explicit discovery and endpoint-bound model selection", async () => {
  const port = api(); render(<LlmControls serverId="server" port={port} />);
  const discover = await screen.findByRole("button", { name: "モデル一覧を取得" });
  fireEvent.click(discover);
  const models = await screen.findByRole("combobox", { name: "LLMモデル" });
  await waitFor(() => expect(models.querySelector('option[value="other"]')).toBeTruthy());
  fireEvent.change(models, { target: { value: "other" } });
  await waitFor(() => expect(port.selectModel).toHaveBeenCalledWith("server", "plan", "other"));
});
it("drops results from the previous conversation server", async () => {
  const port = api(); let resolve!: (rows: LlmEndpoint[]) => void;
  vi.mocked(port.endpoints).mockReturnValueOnce(new Promise((r) => { resolve = r; })).mockResolvedValueOnce([row("new")]);
  const view = render(<LlmControls serverId="old-server" port={port} />);
  view.rerender(<LlmControls serverId="new-server" port={port} />);
  await screen.findByRole("option", { name: "new" });
  resolve([row("old")]); await waitFor(() => expect(screen.queryByRole("option", { name: "old" })).toBeNull());
});
it("keeps existing chat usable when an older server has no controls", async () => {
  const port = api(); vi.mocked(port.endpoints).mockRejectedValue("EndpointNotFound");
  const busy = vi.fn(); render(<LlmControls serverId="older" port={port} onBusy={busy} />);
  expect(await screen.findByText(/このサーバーはLLM選択に対応していません/)).toBeTruthy();
  await waitFor(() => expect(busy).toHaveBeenLastCalledWith(false));
});
