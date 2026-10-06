import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, it, expect, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import { ActivityCoachingSettings } from "./ActivityCoachingSettings";
afterEach(cleanup);
const settings = {
  enabled: false,
  categories: ["development"],
  targetShare: 0.6,
  minimumObservedMinutes: 120,
  minimumCoverage: 0.1,
  maximumUnknownShare: 0.25,
  cooldownDays: 7,
};
function result(
  revision = 4,
  enabled = false,
  patch: Partial<typeof settings> = {},
) {
  return {
    title: "Coaching",
    items: [
      {
        id: null,
        title: "設定",
        fields: [
          ["Scope", "LOCAL_DEVICE_COACHING_SETTINGS"],
          ["Revision", String(revision)],
          ["Settings", JSON.stringify({ ...settings, ...patch, enabled })],
        ],
      },
    ],
  };
}
it("loads explicitly and saves disabled criteria only after reviewing changes", async () => {
  const call = vi
    .fn()
    .mockResolvedValueOnce(result())
    .mockResolvedValueOnce(result(5, false, { targetShare: 0.8 }));
  render(<ActivityCoachingSettings call={call as Command} serverId="s" />);
  expect(call).not.toHaveBeenCalled();
  await userEvent.click(
    screen.getByRole("button", { name: "保存済み設定を取得" }),
  );
  await screen.findByLabelText("基準比率（%）");
  await userEvent.clear(screen.getByLabelText("基準比率（%）"));
  await userEvent.type(screen.getByLabelText("基準比率（%）"), "80");
  await userEvent.click(screen.getByRole("button", { name: "設定変更を確認" }));
  expect(call).toHaveBeenCalledTimes(1);
  await userEvent.click(screen.getByRole("button", { name: "無効状態で保存" }));
  await waitFor(() => expect(call).toHaveBeenCalledTimes(2));
  expect(call.mock.calls[1][1]).toMatchObject({
    serverId: "s",
    operation: {
      operation: "activityCoachingConfigure",
      expectedRevision: 4,
      settings: { enabled: false, targetShare: 0.8 },
    },
  });
});
it("invalidates a review after editing and enables only the saved criteria", async () => {
  const call = vi
    .fn()
    .mockResolvedValueOnce(result())
    .mockResolvedValueOnce(result(5, true));
  render(<ActivityCoachingSettings call={call as Command} serverId="s" />);
  await userEvent.click(
    screen.getByRole("button", { name: "保存済み設定を取得" }),
  );
  await screen.findByLabelText("基準比率（%）");
  await userEvent.click(screen.getByRole("button", { name: "設定変更を確認" }));
  await userEvent.clear(screen.getByLabelText("基準比率（%）"));
  await userEvent.type(screen.getByLabelText("基準比率（%）"), "80");
  expect(screen.queryByRole("button", { name: "無効状態で保存" })).toBeNull();
  await userEvent.click(screen.getByRole("button", { name: "有効化を確認" }));
  expect(call).toHaveBeenCalledTimes(1);
  await userEvent.click(
    screen.getByRole("button", { name: "保存済み基準を有効化" }),
  );
  await waitFor(() => expect(call).toHaveBeenCalledTimes(2));
  expect(call.mock.calls[1][1]).toEqual({
    serverId: "s",
    operation: {
      operation: "activityCoachingEnabled",
      expectedRevision: 4,
      enabled: true,
    },
  });
});
it("requires reload after an uncertain write and never resends automatically", async () => {
  const call = vi
    .fn()
    .mockResolvedValueOnce(result())
    .mockRejectedValueOnce(new Error("conflict"));
  render(<ActivityCoachingSettings call={call as Command} serverId="s" />);
  await userEvent.click(
    screen.getByRole("button", { name: "保存済み設定を取得" }),
  );
  await screen.findByLabelText("基準比率（%）");
  await userEvent.click(screen.getByRole("button", { name: "有効化を確認" }));
  await userEvent.click(
    screen.getByRole("button", { name: "保存済み基準を有効化" }),
  );
  await screen.findByRole("alert");
  expect(screen.queryByLabelText("基準比率（%）")).toBeNull();
  expect(call).toHaveBeenCalledTimes(2);
  expect(
    screen.queryByRole("button", { name: "保存済み基準を有効化" }),
  ).toBeNull();
});
it("does not save invalid criteria or present an unknown scope as editable settings", async () => {
  const unknown = result();
  unknown.items[0].fields[0][1] = "PROJECT";
  const call = vi
    .fn()
    .mockResolvedValueOnce(result())
    .mockResolvedValueOnce(unknown);
  render(<ActivityCoachingSettings call={call as Command} serverId="s" />);
  await userEvent.click(
    screen.getByRole("button", { name: "保存済み設定を取得" }),
  );
  await screen.findByLabelText("基準比率（%）");
  await userEvent.click(screen.getByLabelText("development"));
  await userEvent.click(screen.getByRole("button", { name: "設定変更を確認" }));
  await screen.findByRole("alert");
  expect(screen.queryByRole("button", { name: "無効状態で保存" })).toBeNull();
  expect(call).toHaveBeenCalledTimes(1);
  await userEvent.click(
    screen.getByRole("button", { name: "保存済み設定を取得" }),
  );
  await screen.findByRole("alert");
  expect(screen.queryByLabelText("基準比率（%）")).toBeNull();
});
it("ignores a late write result after switching servers", async () => {
  let finish!: (value: unknown) => void;
  const call = vi
    .fn()
    .mockResolvedValueOnce(result())
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    )
    .mockResolvedValueOnce(result(9));
  const view = render(
    <ActivityCoachingSettings call={call as Command} serverId="old" />,
  );
  await userEvent.click(
    screen.getByRole("button", { name: "保存済み設定を取得" }),
  );
  await screen.findByLabelText("基準比率（%）");
  await userEvent.click(screen.getByRole("button", { name: "有効化を確認" }));
  await userEvent.click(
    screen.getByRole("button", { name: "保存済み基準を有効化" }),
  );
  view.rerender(
    <ActivityCoachingSettings call={call as Command} serverId="new" />,
  );
  await userEvent.click(
    screen.getByRole("button", { name: "保存済み設定を取得" }),
  );
  await screen.findByRole("button", { name: "有効化を確認" });
  finish(result(5, true));
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "有効化を確認" })).toBeTruthy(),
  );
  expect(call).toHaveBeenCalledTimes(3);
  expect(call.mock.calls[2][1].serverId).toBe("new");
});
it("discards a late response when the server becomes locked", async () => {
  let finish!: (value: unknown) => void;
  const call = vi.fn().mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const view = render(
    <ActivityCoachingSettings call={call as Command} serverId="old" />,
  );
  await userEvent.click(
    screen.getByRole("button", { name: "保存済み設定を取得" }),
  );
  view.rerender(
    <ActivityCoachingSettings call={call as Command} serverId={null} />,
  );
  finish(result());
  await waitFor(() =>
    expect(screen.queryByLabelText("基準比率（%）")).toBeNull(),
  );
  expect(call).toHaveBeenCalledTimes(1);
});
