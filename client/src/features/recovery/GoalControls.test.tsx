import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, it, expect, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import { GoalControls } from "./GoalControls";
afterEach(cleanup);
it("attaches the accepted Run through the existing application callback", async () => {
  const accepted = vi.fn();
  const ready = {
    ...goal,
    fields: goal.fields.map(([key, value]) => [
      key,
      key === "Status" ? "READY" : value,
    ]),
  };
  const run = {
    serverId: "s",
    projectId: "p",
    sessionId: "session",
    runId: "goal-run",
  };
  const call = vi.fn().mockImplementation((name, args) =>
    Promise.resolve(
      name === "goal_track"
        ? run
        : {
            title: "Goal",
            items: [args.operation.operation === "goalRun" ? goal : ready],
          },
    ),
  );
  render(
    <GoalControls
      call={call as Command}
      serverId="s"
      projectId="p"
      onAccepted={accepted}
    />,
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "Goalを確認" }),
  );
  await userEvent.click(
    screen.getByRole("button", { name: "Goalの実行内容を確認" }),
  );
  await userEvent.click(
    screen.getByRole("button", { name: "このGoal操作を実行" }),
  );
  await waitFor(() => expect(accepted).toHaveBeenCalledExactlyOnceWith(run));
});
it("ignores late reads from the previously selected Project", async () => {
  let resolveOld!: (value: unknown) => void;
  const call = vi.fn().mockImplementation((_name, args) =>
    args.operation.projectId === "p"
      ? new Promise((resolve) => {
          resolveOld = resolve;
        })
      : Promise.resolve({ title: "Goals", items: [] }),
  );
  const view = render(
    <GoalControls
      call={call as Command}
      serverId="s"
      projectId="p"
      onAccepted={vi.fn()}
    />,
  );
  view.rerender(
    <GoalControls
      call={call as Command}
      serverId="s"
      projectId="other"
      onAccepted={vi.fn()}
    />,
  );
  await screen.findByText("保存されたGoalはありません。");
  resolveOld({ title: "Goals", items: [goal] });
  await waitFor(() =>
    expect(screen.queryByRole("button", { name: "Goalを確認" })).toBeNull(),
  );
});
const goal = {
  id: "g",
  title: "Produce result",
  fields: [
    ["Project", "p"],
    ["Session", "session"],
    ["Status", "RUNNING"],
    ["Run", "old"],
    ["Criteria", "out.txt digest"],
    ["Runs", "1 / 3"],
    ["LLM calls", "2 / 20"],
  ],
};
it("requires fresh inspection, review and explicit unknown-effect acknowledgement before reconciliation", async () => {
  const call = vi
    .fn()
    .mockImplementation(() =>
      Promise.resolve({ title: "Goal", items: [goal] }),
    );
  render(
    <GoalControls
      call={call as Command}
      serverId="s"
      projectId="p"
      onAccepted={vi.fn()}
    />,
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "Goalを確認" }),
  );
  await userEvent.click(
    screen.getByRole("button", { name: "不確定Runの照合内容を確認" }),
  );
  const confirm = screen.getByRole("button", { name: "このGoal操作を実行" });
  expect((confirm as HTMLButtonElement).disabled).toBe(true);
  expect(
    call.mock.calls.every(
      ([name, args]) =>
        name === "workspace_execute" &&
        !args.operation.operation.includes("Reconcile"),
    ),
  ).toBe(true);
  await userEvent.click(
    screen.getByRole("checkbox", { name: /現在の成果物と履歴を確認/ }),
  );
  await userEvent.click(confirm);
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("workspace_execute", {
      serverId: "s",
      operation: {
        operation: "goalReconcile",
        projectId: "p",
        id: "g",
        expectedRunId: "old",
        acknowledgeUncertainSideEffects: true,
      },
    }),
  );
});
it("explicit execution attaches the accepted current Run and never resends after tracking fails", async () => {
  const ready = {
    ...goal,
    fields: goal.fields.map(([key, value]) => [
      key,
      key === "Status" ? "READY" : value,
    ]),
  };
  const call = vi.fn().mockImplementation((name, args) =>
    name === "goal_track"
      ? Promise.reject("RunNotFound")
      : Promise.resolve({
          title: "Goal",
          items: [args.operation.operation === "goalRun" ? goal : ready],
        }),
  );
  render(
    <GoalControls
      call={call as Command}
      serverId="s"
      projectId="p"
      onAccepted={vi.fn()}
    />,
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "Goalを確認" }),
  );
  await userEvent.click(
    screen.getByRole("button", { name: "Goalの実行内容を確認" }),
  );
  await userEvent.click(
    screen.getByRole("button", { name: "このGoal操作を実行" }),
  );
  await screen.findByRole("alert");
  expect(
    call.mock.calls.filter(
      ([, args]) => args.operation?.operation === "goalRun",
    ),
  ).toHaveLength(1);
  expect(
    call.mock.calls.filter(([name]) => name === "goal_track"),
  ).toHaveLength(1);
});
