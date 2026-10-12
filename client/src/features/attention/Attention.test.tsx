import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, it, expect, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import { Attention } from "./Attention";
afterEach(cleanup);
it("continues only the reviewed saved invocation and suppresses duplicate clicks", async () => {
  let release: (() => void) | undefined;
  const saved = {
    id: "saved",
    title: "runCommand",
    fields: [
      ["Arguments", "npm run textlint"],
      ["Status", "PENDING"],
      ["Project", "p"],
      ["Session", "session"],
      ["Continuation", "WAITING_APPROVAL"],
    ],
  };
  const call = vi.fn().mockImplementation((_name, args) => {
    if (args.operation.operation === "approvalContinue")
      return new Promise((resolve) => {
        release = () => resolve({ title: "Accepted", items: [] });
      });
    return Promise.resolve({
      title: "items",
      items: args.operation.operation === "approvals" ? [saved] : [],
    });
  });
  render(
    <Attention
      call={call as Command}
      serverId="s"
      projects={[{ id: "p", name: "Project", path: "/project" }]}
    />,
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "承認内容を確認" }),
  );
  expect(screen.getAllByText("npm run textlint")).toHaveLength(2);
  const button = screen.getByRole("button", { name: "承認して続行" });
  await userEvent.dblClick(button);
  expect(
    call.mock.calls.filter(
      ([, args]) => args.operation.operation === "approvalContinue",
    ),
  ).toHaveLength(1);
  expect(call).toHaveBeenCalledWith("workspace_execute", {
    serverId: "s",
    operation: { operation: "approvalContinue", projectId: "p", id: "saved" },
  });
  release!();
  expect(
    await screen.findByText(
      "続行を受け付けました。保存した呼び出しから再開します。",
    ),
  ).toBeTruthy();
});
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
