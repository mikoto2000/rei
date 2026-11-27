import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { Chat } from "./Chat";
import type { Command } from "../../tauri/commands";
afterEach(cleanup);
it("loads LLM controls using the conversation server rather than a settings selection", async () => {
  const call = vi.fn().mockRejectedValue("EndpointNotFound");
  render(<Chat llmCall={call as Command} conversation={{ localId: "chat", serverProfileId: "conversation-server", projectId: "project", sessionId: "session", title: "Chat", createdAt: 0, lastAccessedAt: 0 }} projectName="Project" runs={[]} pending={false} error={null} onSend={vi.fn()} onStop={vi.fn()} onContinue={vi.fn()} onRefresh={vi.fn()} onSubscribe={vi.fn()} />);
  await waitFor(() => expect(call).toHaveBeenCalledWith("llm_endpoints", { serverId: "conversation-server" }));
  expect(await screen.findByText(/このサーバーはLLM選択に対応していません/)).toBeTruthy();
});
