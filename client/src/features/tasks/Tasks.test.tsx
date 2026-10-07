import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import { Tasks } from "./Tasks";
afterEach(cleanup);
it("resumes only on explicit input and does not retry a rejected operation", async () => {
  const call = vi.fn().mockImplementation((name) =>
    name === "tasks_list"
      ? Promise.resolve({
          items: [
            {
              ...task,
              kind: "CHECKPOINT",
              status: "SUSPENDED",
              runId: "old",
              revision: 9,
              cancelSupported: false,
              resumeSupported: true,
            },
          ],
          nextCursor: null,
        })
      : Promise.reject({ code: "CONFLICT" }),
  );
  render(<Tasks call={call as Command} serverId="s" projects={[]} />);
  await screen.findByText("SUSPENDED");
  expect(call.mock.calls.some(([name]) => name === "task_control")).toBe(false);
  await userEvent.click(screen.getByRole("button", { name: "再開" }));
  await screen.findByRole("alert");
  expect(call).toHaveBeenCalledWith("task_control", {
    serverId: "s",
    projectId: "p",
    sessionId: "session",
    taskId: "run:r",
    expectedRunId: "old",
    expectedRevision: 9,
    action: "resume",
  });
  expect(
    call.mock.calls.filter(([name]) => name === "task_control"),
  ).toHaveLength(1);
  expect(screen.getByText("SUSPENDED")).toBeTruthy();
});
it("paginates with the same Project and Session filters", async () => {
  const call = vi.fn().mockResolvedValue({ items: [], nextCursor: "next" });
  render(<Tasks call={call as Command} serverId="s" projects={[]} />);
  await userEvent.type(screen.getByLabelText("TaskのSession"), "session");
  await userEvent.click(screen.getByRole("button", { name: "絞り込む" }));
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("tasks_list", {
      serverId: "s",
      projectId: null,
      sessionId: "session",
      limit: 50,
      cursor: null,
    }),
  );
  await userEvent.click(screen.getByRole("button", { name: "さらに読み込む" }));
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("tasks_list", {
      serverId: "s",
      projectId: null,
      sessionId: "session",
      limit: 50,
      cursor: "next",
    }),
  );
});
it("submits once through Task Manager and exposes the accepted server receipt", async () => {
  const call = vi
    .fn()
    .mockImplementation((name) =>
      Promise.resolve(
        name === "tasks_list"
          ? { items: [], nextCursor: null }
          : { ...task, status: "QUEUED" },
      ),
    );
  render(
    <Tasks
      call={call as Command}
      serverId="s"
      projects={[{ id: "p", name: "rei", path: "/project" }]}
    />,
  );
  await screen.findByText("Taskはありません。");
  await userEvent.selectOptions(screen.getByLabelText("TaskのProject"), "p");
  await userEvent.type(screen.getByLabelText("新しいTaskの依頼"), "確認する");
  await userEvent.click(screen.getByRole("button", { name: "Taskを開始" }));
  expect(await screen.findByText("QUEUED")).toBeTruthy();
  expect(call).toHaveBeenCalledWith("task_submit", {
    serverId: "s",
    projectId: "p",
    message: "確認する",
  });
  expect(
    call.mock.calls.filter(([name]) => name === "task_submit"),
  ).toHaveLength(1);
});
const task = {
  id: "run:r",
  kind: "RUN",
  sourceId: "r",
  projectId: "p",
  sessionId: "session",
  runId: "r",
  status: "RUNNING",
  revision: 4,
  cancelSupported: true,
  resumeSupported: false,
  inputSupported: false,
  results: [],
  dependencyIds: [],
  childIds: [],
};
it("loads persisted server tasks and controls the selected owner and revision", async () => {
  const call = vi
    .fn()
    .mockImplementation((name) =>
      Promise.resolve(
        name === "tasks_list"
          ? { items: [task], nextCursor: null }
          : { ...task, status: "CANCELLED", cancelSupported: false },
      ),
    );
  render(<Tasks call={call as Command} serverId="s" projects={[]} />);
  expect(await screen.findByText("RUNNING")).toBeTruthy();
  await userEvent.click(screen.getByRole("button", { name: "中止" }));
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("task_control", {
      serverId: "s",
      projectId: "p",
      sessionId: "session",
      taskId: "run:r",
      expectedRunId: "r",
      expectedRevision: 4,
      action: "cancel",
    }),
  );
  expect(await screen.findByText("CANCELLED")).toBeTruthy();
  expect(call.mock.calls.some(([name]) => name === "chat_submit")).toBe(false);
});
it("discards a previous server response after the selected server changes", async () => {
  let resolve!: (value: unknown) => void;
  const call = vi.fn().mockImplementation((_name, args) =>
    args.serverId === "s"
      ? new Promise((r) => {
          resolve = r;
        })
      : Promise.resolve({
          items: [
            {
              ...task,
              id: "run:new",
              status: "UNKNOWN",
              cancelSupported: false,
            },
          ],
          nextCursor: null,
        }),
  );
  const view = render(
    <Tasks call={call as Command} serverId="s" projects={[]} />,
  );
  view.rerender(<Tasks call={call as Command} serverId="new" projects={[]} />);
  expect(await screen.findByText("UNKNOWN")).toBeTruthy();
  resolve({ items: [task], nextCursor: null });
  await waitFor(() => expect(screen.queryByText("RUNNING")).toBeNull());
  expect(screen.queryByRole("button", { name: "中止" })).toBeNull();
});
