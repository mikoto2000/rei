import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { Chat } from "./Chat";
import type { Command } from "../../tauri/commands";
import type { LlmEndpoint } from "../../entities/llm";
afterEach(cleanup);
it("blocks ordinary send synchronously until a connection mutation settles", async () => {
  const row = (id: string): LlmEndpoint => ({
    profile: {
      id,
      model: "fixture",
      provider: "openai-compatible",
      api: "chat-completions",
      authentication: "none",
      accountRef: null,
      usagePath: "local-llm",
      capabilities: { route: {}, server: {}, model: {}, effective: {} },
    },
    active: true,
    reasoningReplay: "NONE",
    authenticationState: "not-required",
    unavailableReason: null,
    generationSettings: {},
    unappliedSettings: {},
    diagnostics: [],
    selectionScope: "next Run only",
  });
  let resolve!: (value: LlmEndpoint) => void;
  const pending = new Promise<LlmEndpoint>((r) => {
    resolve = r;
  });
  const call = vi.fn().mockImplementation(async (name: string) => {
    if (name === "llm_endpoints")
      return [row("local"), { ...row("second"), active: false }];
    if (name === "llm_select") return pending;
    throw "EndpointNotFound";
  });
  const send = vi.fn().mockResolvedValue(false);
  render(
    <Chat
      llmCall={call as Command}
      conversation={{
        localId: "chat",
        serverProfileId: "conversation-server",
        projectId: "project",
        sessionId: "session",
        title: "Chat",
        createdAt: 0,
        lastAccessedAt: 0,
      }}
      projectName="Project"
      runs={[]}
      pending={false}
      error={null}
      onSend={send}
      onStop={vi.fn()}
      onContinue={vi.fn()}
      onRefresh={vi.fn()}
      onSubscribe={vi.fn()}
    />,
  );
  const selector = await screen.findByRole("combobox", { name: "LLM接続先" });
  const text = screen.getByRole("textbox", { name: "メッセージ" });
  fireEvent.change(text, { target: { value: "fixture message" } });
  fireEvent.change(selector, { target: { value: "second" } });
  fireEvent.submit(text.closest("form")!);
  expect(send).not.toHaveBeenCalled();
  expect(
    screen
      .getByRole("button", { name: "送信", exact: true })
      .hasAttribute("disabled"),
  ).toBe(true);
  resolve(row("second"));
  await waitFor(() =>
    expect(
      screen
        .getByRole("button", { name: "送信", exact: true })
        .hasAttribute("disabled"),
    ).toBe(false),
  );
  fireEvent.submit(text.closest("form")!);
  await waitFor(() => expect(send).toHaveBeenCalledWith("fixture message"));
});
it("loads LLM controls using the conversation server rather than a settings selection", async () => {
  const call = vi.fn().mockRejectedValue("EndpointNotFound");
  render(
    <Chat
      llmCall={call as Command}
      conversation={{
        localId: "chat",
        serverProfileId: "conversation-server",
        projectId: "project",
        sessionId: "session",
        title: "Chat",
        createdAt: 0,
        lastAccessedAt: 0,
      }}
      projectName="Project"
      runs={[]}
      pending={false}
      error={null}
      onSend={vi.fn()}
      onStop={vi.fn()}
      onContinue={vi.fn()}
      onRefresh={vi.fn()}
      onSubscribe={vi.fn()}
    />,
  );
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("llm_endpoints", {
      serverId: "conversation-server",
    }),
  );
  expect(
    await screen.findByText(/このサーバーはLLM選択に対応していません/),
  ).toBeTruthy();
});
