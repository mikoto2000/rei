import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, it, expect, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import { Recovery } from "./Recovery";
afterEach(cleanup);
const item = {
  id: "task",
  title: "Expected outcome",
  fields: [
    ["Project", "p"],
    ["Session", "session"],
    ["Run", "old"],
    ["Status", "INTERRUPTED"],
    ["Revision", "3"],
  ],
};
it("inspects before explicit resume and attaches the accepted run", async () => {
  const accepted = vi.fn();
  const call = vi.fn().mockImplementation((name, args) =>
    name === "checkpoint_resume"
      ? Promise.resolve({
          runId: "new",
          serverId: "s",
          projectId: "p",
          sessionId: "session",
        })
      : Promise.resolve({
          title: "Checkpoint",
          items:
            args.operation.operation === "checkpointInspect"
              ? [
                  {
                    id: "task",
                    title: "Reconciliation",
                    fields: [
                      ["Project", "p"],
                      ["Decision", "CONTINUE"],
                      ["Next", "verify"],
                    ],
                  },
                ]
              : [item],
        }),
  );
  render(
    <Recovery
      call={call as Command}
      serverId="s"
      projects={[{ id: "p", name: "rei", path: "/project" }]}
      onAccepted={accepted}
    />,
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "状態を照合" }),
  );
  expect(await screen.findByText("verify")).toBeTruthy();
  expect(call.mock.calls.some(([name]) => name === "checkpoint_resume")).toBe(
    false,
  );
  await userEvent.click(screen.getByRole("button", { name: "再開内容を確認" }));
  await userEvent.click(screen.getByRole("button", { name: "このTaskを再開" }));
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("checkpoint_resume", {
      serverId: "s",
      projectId: "p",
      taskId: "task",
    }),
  );
  expect(accepted).toHaveBeenCalled();
});
it("does not resume a blocked checkpoint", async () => {
  const call = vi.fn().mockImplementation((_name, args) =>
    Promise.resolve({
      title: "Checkpoint",
      items:
        args.operation.operation === "checkpointInspect"
          ? [
              {
                id: "task",
                title: "Reconciliation",
                fields: [
                  ["Project", "p"],
                  ["Decision", "BLOCKED"],
                  ["Blockers", "Task already executing"],
                ],
              },
            ]
          : [item],
    }),
  );
  render(
    <Recovery
      call={call as Command}
      serverId="s"
      projects={[{ id: "p", name: "rei", path: "/project" }]}
      onAccepted={vi.fn()}
    />,
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "状態を照合" }),
  );
  expect(await screen.findByText("Task already executing")).toBeTruthy();
  expect(screen.queryByRole("button", { name: "再開内容を確認" })).toBeNull();
});
