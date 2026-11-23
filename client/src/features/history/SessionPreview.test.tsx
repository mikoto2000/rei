import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { afterEach, it, expect, vi } from "vitest";
import { SessionPreview } from "./SessionPreview";
import type { Command } from "../../tauri/commands";
afterEach(cleanup);
it("reads forward to the latest turn only when requested", async () => {
  const call = vi.fn().mockImplementation((_name, args) =>
    Promise.resolve(
      args.cursor
        ? {
            items: [{ assistantMessage: "最新の回答", userMessage: "質問" }],
            nextCursor: null,
          }
        : {
            items: [{ assistantMessage: "古い回答", userMessage: "質問" }],
            nextCursor: "next",
          },
    ),
  );
  render(
    <SessionPreview
      call={call as Command}
      serverId="s"
      sessionId="c"
      updatedAt="today"
    />,
  );
  expect(call).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "最新の発言を確認" }));
  expect(await screen.findByText("最新の回答")).toBeTruthy();
  expect(call).toHaveBeenLastCalledWith("session_turns", {
    serverId: "s",
    sessionId: "c",
    limit: 100,
    cursor: "next",
  });
});
it("stops pagination when the preview closes", async () => {
  let release!: (value: unknown) => void;
  const call = vi.fn().mockImplementation(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  render(
    <SessionPreview
      call={call as Command}
      serverId="s"
      sessionId="c"
      updatedAt="today"
    />,
  );
  fireEvent.click(screen.getByRole("button", { name: "最新の発言を確認" }));
  fireEvent.click(screen.getByRole("button", { name: "プレビューを閉じる" }));
  release({ items: [], nextCursor: "next" });
  await Promise.resolve();
  expect(call).toHaveBeenCalledTimes(1);
  expect(screen.queryByText("最新の発言を読み込んでいます…")).toBeNull();
});
