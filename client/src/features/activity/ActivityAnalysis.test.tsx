import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, it, expect, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import { ActivityAnalysis } from "./ActivityAnalysis";
afterEach(cleanup);
it("passes registered projects into the observation context reader without an automatic read", async () => {
  const call = vi.fn();
  render(
    <ActivityAnalysis
      call={call as Command}
      serverId="s"
      projects={[{ id: "p", name: "registered", path: "private" }]}
    />,
  );
  await userEvent.selectOptions(screen.getByLabelText("文脈のProject"), "p");
  expect(
    (
      screen.getByRole("button", {
        name: "保存観測時の文脈を取得",
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(false);
  expect(call).not.toHaveBeenCalled();
});
it("includes explicit coaching controls without starting any settings request on navigation", () => {
  const call = vi.fn();
  render(<ActivityAnalysis call={call as Command} serverId="s" />);
  expect(
    screen.getByRole("button", { name: "保存済み設定を取得" }),
  ).toBeTruthy();
  expect(call).not.toHaveBeenCalled();
});
it("discards a previous period read after the user changes the analysis selection", async () => {
  let finish!: (value: unknown) => void;
  const call = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    )
    .mockResolvedValue({
      title: "Activity",
      items: [
        { id: null, title: "月次", fields: [["Report", "新しい月次分析"]] },
      ],
    });
  render(<ActivityAnalysis call={call as Command} serverId="s" />);
  await userEvent.click(screen.getByRole("button", { name: "保存観測を分析" }));
  await userEvent.selectOptions(screen.getByLabelText("分析期間"), "MONTH");
  await userEvent.click(screen.getByRole("button", { name: "保存観測を分析" }));
  await screen.findByText("新しい月次分析");
  finish(result);
  await waitFor(() =>
    expect(
      screen.queryByText("観測推定量。成果の実測ではありません。"),
    ).toBeNull(),
  );
  expect(call).toHaveBeenCalledTimes(2);
});
const result = {
  title: "Activity",
  items: [
    {
      id: null,
      title: "週次分析",
      fields: [
        ["Report", "観測推定量。成果の実測ではありません。"],
        ["Zone", "Asia/Tokyo"],
        ["Partial", "false"],
      ],
    },
  ],
};
it("reads a chosen calendar period only after the explicit button", async () => {
  const call = vi.fn().mockResolvedValue(result);
  render(<ActivityAnalysis call={call as Command} serverId="s" />);
  expect(call).not.toHaveBeenCalled();
  await userEvent.selectOptions(screen.getByLabelText("分析期間"), "MONTH");
  await userEvent.type(screen.getByLabelText("対象日"), "2026-09-23");
  await userEvent.click(screen.getByRole("button", { name: "保存観測を分析" }));
  await screen.findByText("観測推定量。成果の実測ではありません。");
  expect(call).toHaveBeenCalledExactlyOnceWith("workspace_execute", {
    serverId: "s",
    operation: {
      operation: "activityAnalysis",
      period: "MONTH",
      date: "2026-09-23",
    },
  });
});
it("ignores a late response from a different server and keeps the locked state quiet", async () => {
  let resolve!: (value: unknown) => void;
  const call = vi.fn().mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  const view = render(
    <ActivityAnalysis call={call as Command} serverId="old" />,
  );
  await userEvent.click(screen.getByRole("button", { name: "保存観測を分析" }));
  view.rerender(<ActivityAnalysis call={call as Command} serverId={null} />);
  resolve(result);
  await waitFor(() =>
    expect(
      screen.queryByText("観測推定量。成果の実測ではありません。"),
    ).toBeNull(),
  );
  expect(
    (
      screen.getByRole("button", {
        name: "保存観測を分析",
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  expect(call).toHaveBeenCalledTimes(1);
});
it("does not automatically retry a failed read or execute background work", async () => {
  const call = vi.fn().mockRejectedValue(new Error("offline"));
  render(<ActivityAnalysis call={call as Command} serverId="s" />);
  await userEvent.click(screen.getByRole("button", { name: "保存観測を分析" }));
  await screen.findByRole("alert");
  expect(call).toHaveBeenCalledTimes(1);
  await userEvent.click(screen.getByRole("button", { name: "保存観測を分析" }));
  await waitFor(() => expect(call).toHaveBeenCalledTimes(2));
});
