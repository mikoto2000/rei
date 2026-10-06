import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, it, expect, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import { HumanAnswers } from "./HumanAnswers";
afterEach(cleanup);
it("ignores a delayed read after switching Project", async () => {
  let resolveOld!: (value: unknown) => void;
  const call = vi.fn().mockImplementation((_name, args) =>
    args.operation.projectId === "p"
      ? new Promise((resolve) => {
          resolveOld = resolve;
        })
      : Promise.resolve({ title: "Dependencies", items: [] }),
  );
  const view = render(
    <HumanAnswers call={call as Command} serverId="s" projectId="p" />,
  );
  view.rerender(
    <HumanAnswers call={call as Command} serverId="s" projectId="other" />,
  );
  await screen.findByText("人間回答待ちの依存はありません。");
  resolveOld({ title: "Dependencies", items: [question] });
  await waitFor(() => expect(screen.queryByText("Choose A or B")).toBeNull());
  expect(screen.queryByRole("textbox", { name: "回答" })).toBeNull();
});
const question = {
  id: "dep",
  title: "Choose A or B",
  fields: [
    ["Project", "p"],
    ["Session", "session"],
    ["Kind", "USER_ANSWER"],
    ["State", "WAITING"],
    ["Version", "3"],
    ["Answer", ""],
  ],
};
it("sends the reviewed answer and version only after explicit confirmation", async () => {
  const call = vi.fn().mockImplementation((_name, args) =>
    Promise.resolve({
      title: "Dependencies",
      items: [
        args.operation.operation === "dependencyAnswer"
          ? {
              ...question,
              fields: question.fields.map(([k, v]) => [
                k,
                k === "Answer" ? "Choice A" : k === "Version" ? "4" : v,
              ]),
            }
          : question,
      ],
    }),
  );
  render(<HumanAnswers call={call as Command} serverId="s" projectId="p" />);
  await screen.findByText("Choose A or B");
  await userEvent.type(
    screen.getByRole("textbox", { name: "回答" }),
    "Choice A",
  );
  await userEvent.click(screen.getByRole("button", { name: "回答内容を確認" }));
  expect(
    call.mock.calls.filter(
      ([, a]) => a.operation.operation === "dependencyAnswer",
    ),
  ).toHaveLength(0);
  await userEvent.click(screen.getByRole("button", { name: "この回答を保存" }));
  await waitFor(() =>
    expect(call).toHaveBeenCalledWith("workspace_execute", {
      serverId: "s",
      operation: {
        operation: "dependencyAnswer",
        projectId: "p",
        id: "dep",
        expectedVersion: 3,
        answer: "Choice A",
      },
    }),
  );
  expect(await screen.findByText(/回答を保存しました/)).toBeTruthy();
});
it("clears the reviewed question on owner change and never retries a failed answer", async () => {
  const call = vi.fn().mockImplementation((_name, args) =>
    args.operation.operation === "dependencyAnswer"
      ? Promise.reject("ResourceConflict")
      : Promise.resolve({
          title: "Dependencies",
          items: args.operation.projectId === "p" ? [question] : [],
        }),
  );
  const view = render(
    <HumanAnswers call={call as Command} serverId="s" projectId="p" />,
  );
  await screen.findByText("Choose A or B");
  await userEvent.type(screen.getByRole("textbox", { name: "回答" }), "A");
  await userEvent.click(screen.getByRole("button", { name: "回答内容を確認" }));
  await userEvent.click(screen.getByRole("button", { name: "この回答を保存" }));
  await screen.findByRole("alert");
  expect(
    call.mock.calls.filter(
      ([, a]) => a.operation.operation === "dependencyAnswer",
    ),
  ).toHaveLength(1);
  expect(screen.queryByRole("button", { name: "この回答を保存" })).toBeNull();
  view.rerender(
    <HumanAnswers call={call as Command} serverId="s" projectId="other" />,
  );
  await screen.findByText("人間回答待ちの依存はありません。");
  expect(screen.queryByText("Choose A or B")).toBeNull();
});
