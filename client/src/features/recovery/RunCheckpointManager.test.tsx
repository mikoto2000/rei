import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import { RunCheckpointManager } from "./RunCheckpointManager";

afterEach(cleanup);

it("confirms the selected task and refreshes the run and checkpoint after discard", async () => {
  const user = userEvent.setup();
  let discarded = false;
  const call = vi.fn().mockImplementation(async (_name, { operation }) => {
    switch (operation.operation) {
      case "managedRuns":
        return {
          items: [
            {
              id: "failed",
              title: "Original request",
              fields: [
                ["Project", "p"],
                ["Project name", "rei"],
                ["Status", "FAILED"],
                ["Failure", "Failure reason"],
              ],
            },
            {
              id: "empty",
              title: "No saved state",
              fields: [
                ["Project", "p"],
                ["Status", "FAILED"],
              ],
            },
          ],
        };
      case "runCheckpoints":
        return {
          items:
            operation.runId === "empty"
              ? []
              : [
                  {
                    id: "task",
                    title: "Task request",
                    fields: [
                      ["Run", "resumed"],
                      ["Original run", "failed"],
                      ["Revision", discarded ? "4" : "3"],
                      ["Status", discarded ? "ABANDONED" : "FAILED"],
                      ["Discardable", String(!discarded)],
                    ],
                  },
                ],
        };
      case "discardRunCheckpoint":
        discarded = true;
        return { items: [] };
      default:
        throw new Error("Unexpected operation");
    }
  });
  render(<RunCheckpointManager call={call as Command} serverId="server" />);
  await user.click(
    screen.getByRole("button", { name: "実行とチェックポイントを管理" }),
  );
  await user.click(
    await screen.findByRole("button", { name: /No saved state/ }),
  );
  await screen.findByText("チェックポイントがありません。");
  await user.click(screen.getByRole("button", { name: /Original request/ }));
  await user.click(
    await screen.findByRole("button", { name: "破棄内容を確認" }),
  );
  await user.click(screen.getByRole("button", { name: "戻る" }));
  expect(discarded).toBe(false);
  await user.click(screen.getByRole("button", { name: "破棄内容を確認" }));
  expect(
    screen.getByText("このタスクをチェックポイントから再開できなくなります"),
  ).toBeTruthy();
  await user.click(
    screen.getByRole("button", { name: "チェックポイントを破棄" }),
  );
  expect(call).toHaveBeenCalledWith("workspace_execute", {
    serverId: "server",
    operation: {
      operation: "discardRunCheckpoint",
      projectId: "p",
      runId: "failed",
      taskId: "task",
      revision: "3",
    },
  });
  await screen.findByText(/ABANDONED.*revision 4/);
  await waitFor(() =>
    expect(
      (
        screen.getByRole("button", {
          name: "破棄内容を確認",
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true),
  );
  expect(
    call.mock.calls.filter(
      ([, args]) => args.operation.operation === "managedRuns",
    ),
  ).toHaveLength(2);
});
