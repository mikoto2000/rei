import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, it, expect, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import { Attention } from "./Attention";
afterEach(cleanup);
const project = { id: "p", name: "Project", path: "/project" };
const item = {
  id: "notice",
  title: "RUN_FAILED",
  fields: [
    ["Message", "Inspect result"],
    ["Status", "OPEN"],
    ["Project", "p"],
    ["Session", "session"],
  ],
};
const approval = {
  id: "request",
  title: "writeFile",
  fields: [
    ["Arguments", "redacted arguments"],
    ["Status", "PENDING"],
    ["Project", "p"],
    ["Session", "session"],
  ],
};
it("reads owned items and performs only explicit ack or approval", async () => {
  const call = vi.fn().mockImplementation((_name, args) =>
    Promise.resolve({
      title: "items",
      items:
        args.operation.operation === "attention"
          ? [item]
          : args.operation.operation === "approvals"
            ? [approval]
            : [],
    }),
  );
  render(
    <Attention call={call as Command} serverId="s" projects={[project]} />,
  );
  expect(await screen.findByText("Inspect result")).toBeTruthy();
  expect(await screen.findByText("redacted arguments")).toBeTruthy();
  expect(
    call.mock.calls.every(([, args]) =>
      ["attention", "approvals"].includes(args.operation.operation),
    ),
  ).toBe(true);
  await userEvent.click(
    screen.getByRole("button", { name: "通知を確認済みにする" }),
  );
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("workspace_execute", {
      serverId: "s",
      operation: { operation: "attentionAck", projectId: "p", id: "notice" },
    }),
  );
  await userEvent.click(screen.getByRole("button", { name: "承認内容を確認" }));
  expect(
    screen.getByText("このTool呼び出しを一回だけ承認しますか？"),
  ).toBeTruthy();
  expect(
    call.mock.calls.some(
      ([, args]) => args.operation.operation === "approvalDecision",
    ),
  ).toBe(false);
  await userEvent.click(screen.getByRole("button", { name: "一回だけ承認" }));
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("workspace_execute", {
      serverId: "s",
      operation: {
        operation: "approvalDecision",
        projectId: "p",
        id: "request",
        approved: true,
      },
    }),
  );
  expect(
    call.mock.calls.some(
      ([name]) => name === "chat_submit" || name === "background_submit",
    ),
  ).toBe(false);
});
it("ignores delayed results after a server switch", async () => {
  const resolvers: Array<(value: unknown) => void> = [];
  const call = vi.fn().mockImplementation((_name, args) =>
    args.serverId === "old"
      ? new Promise((r) => {
          resolvers.push(r);
        })
      : Promise.resolve({ title: "empty", items: [] }),
  );
  const view = render(
    <Attention call={call as Command} serverId="old" projects={[project]} />,
  );
  view.rerender(
    <Attention call={call as Command} serverId="new" projects={[project]} />,
  );
  resolvers.forEach((resolve) => resolve({ title: "late", items: [item] }));
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("workspace_execute", {
      serverId: "new",
      operation: { operation: "attention", projectId: "p" },
    }),
  );
  expect(screen.queryByText("Inspect result")).toBeNull();
});

it("rejects explicitly and hides mismatched project rows", async () => {
  const call = vi.fn().mockImplementation((_name, args) =>
    Promise.resolve({
      title: "items",
      items:
        args.operation.operation === "attention"
          ? [
              {
                ...item,
                fields: [
                  ["Project", "other"],
                  ["Message", "foreign secret"],
                ],
              },
            ]
          : args.operation.operation === "approvals"
            ? [approval]
            : [],
    }),
  );
  render(
    <Attention call={call as Command} serverId="s" projects={[project]} />,
  );
  expect(await screen.findByRole("button", { name: "拒否" })).toBeTruthy();
  expect(screen.queryByText("foreign secret")).toBeNull();
  await userEvent.click(screen.getByRole("button", { name: "拒否" }));
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("workspace_execute", {
      serverId: "s",
      operation: {
        operation: "approvalDecision",
        projectId: "p",
        id: "request",
        approved: false,
      },
    }),
  );
});
