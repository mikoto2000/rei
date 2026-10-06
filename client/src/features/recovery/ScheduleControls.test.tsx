import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, it, expect, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import { ScheduleControls } from "./ScheduleControls";
afterEach(cleanup);
const saved = {
  id: "timer",
  title: "Inspect result",
  fields: [
    ["Project", "p"],
    ["Session", "session"],
    ["Status", "RUNNING"],
    ["Run", "schedule-run"],
    ["History", "claimed"],
  ],
};
it("requires explicit review and acknowledgement before exact saved Run reconciliation", async () => {
  const call = vi
    .fn()
    .mockResolvedValue({ title: "Schedules", items: [saved] });
  render(
    <ScheduleControls
      call={call as Command}
      serverId="s"
      projectId="p"
      onAccepted={vi.fn()}
    />,
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "予約を確認" }),
  );
  expect(
    call.mock.calls.every(([, args]) =>
      ["schedules", "schedule", "scheduleHistory"].includes(
        args.operation.operation,
      ),
    ),
  ).toBe(true);
  await userEvent.click(
    screen.getByRole("button", { name: "不確定予約の照合内容を確認" }),
  );
  const confirm = screen.getByRole("button", { name: "この予約操作を実行" });
  expect((confirm as HTMLButtonElement).disabled).toBe(true);
  await userEvent.click(screen.getByRole("checkbox"));
  await userEvent.click(confirm);
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("workspace_execute", {
      serverId: "s",
      operation: {
        operation: "scheduleReconcile",
        projectId: "p",
        id: "timer",
        expectedRunId: "schedule-run",
        acknowledgeUncertainSideEffects: true,
      },
    }),
  );
});
it("requires confirmation before activation and attaches the actual Run", async () => {
  const pending = {
    ...saved,
    fields: saved.fields.map(([key, value]) => [
      key,
      key === "Status" ? "PENDING" : key === "Run" ? "" : value,
    ]),
  };
  const run = {
    serverId: "s",
    projectId: "p",
    sessionId: "session",
    runId: "schedule-run",
  };
  const accepted = vi.fn();
  const call = vi.fn().mockImplementation((name, args) =>
    Promise.resolve(
      name === "schedule_track"
        ? run
        : {
            title: "Schedules",
            items: [
              args.operation.operation === "scheduleActivate" ? saved : pending,
            ],
          },
    ),
  );
  render(
    <ScheduleControls
      call={call as Command}
      serverId="s"
      projectId="p"
      onAccepted={accepted}
    />,
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "予約を確認" }),
  );
  await userEvent.click(
    screen.getByRole("button", { name: "予約の有効化内容を確認" }),
  );
  expect(
    call.mock.calls.some(
      ([, args]) => args.operation?.operation === "scheduleActivate",
    ),
  ).toBe(false);
  await userEvent.click(
    screen.getByRole("button", { name: "この予約操作を実行" }),
  );
  await waitFor(() => expect(accepted).toHaveBeenCalledExactlyOnceWith(run));
});
it("does not resend a failed mutation and clears its stale preview", async () => {
  const call = vi
    .fn()
    .mockImplementation((_name, args) =>
      args.operation.operation === "scheduleReconcile"
        ? Promise.reject("ResourceConflict")
        : Promise.resolve({ title: "Schedules", items: [saved] }),
    );
  render(
    <ScheduleControls
      call={call as Command}
      serverId="s"
      projectId="p"
      onAccepted={vi.fn()}
    />,
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "予約を確認" }),
  );
  await userEvent.click(
    screen.getByRole("button", { name: "不確定予約の照合内容を確認" }),
  );
  await userEvent.click(screen.getByRole("checkbox"));
  await userEvent.click(
    screen.getByRole("button", { name: "この予約操作を実行" }),
  );
  await screen.findByRole("alert");
  expect(
    screen.queryByRole("button", { name: "この予約操作を実行" }),
  ).toBeNull();
  expect(
    call.mock.calls.filter(
      ([, args]) => args.operation.operation === "scheduleReconcile",
    ),
  ).toHaveLength(1);
});
it("ignores a delayed list from the previous Project", async () => {
  let resolveOld!: (value: unknown) => void;
  const call = vi.fn().mockImplementation((_name, args) =>
    args.operation.projectId === "p"
      ? new Promise((resolve) => {
          resolveOld = resolve;
        })
      : Promise.resolve({ title: "Schedules", items: [] }),
  );
  const view = render(
    <ScheduleControls
      call={call as Command}
      serverId="s"
      projectId="p"
      onAccepted={vi.fn()}
    />,
  );
  view.rerender(
    <ScheduleControls
      call={call as Command}
      serverId="s"
      projectId="other"
      onAccepted={vi.fn()}
    />,
  );
  await screen.findByText("保存された予約はありません。");
  resolveOld({ title: "Schedules", items: [saved] });
  await waitFor(() => expect(screen.queryByText("Inspect result")).toBeNull());
});
