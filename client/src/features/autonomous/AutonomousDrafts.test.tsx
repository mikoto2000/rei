import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import type { Command } from "../../tauri/commands";
import type {
  WorkspaceOperation,
  WorkspaceResult,
} from "../../entities/workspace";
import { AutonomousDrafts } from "./AutonomousDrafts";

afterEach(cleanup);

it("publishes only after reviewing the full selected body and target, and suppresses duplicate clicks", async () => {
  let finish: ((value: WorkspaceResult) => void) | undefined;
  const f = fixture((_server, op) =>
    op.operation === "autonomousPublish"
      ? new Promise((resolve) => {
          finish = resolve;
        })
      : undefined,
  );
  render(<AutonomousDrafts call={f.call as Command} serverId="s" />);
  await openDraft();
  expect(
    f.operations().some((op) => op.operation === "autonomousPublish"),
  ).toBe(false);
  await userEvent.click(screen.getByRole("button", { name: "公開内容を確認" }));
  const confirmation = screen.getByRole("dialog", { name: "投稿の公開確認" });
  expect(
    within(confirmation).getByLabelText("公開する本文の全文").textContent,
  ).toBe("最初の本文");
  expect(within(confirmation).getByText(/rei.example/)).toBeTruthy();
  expect(within(confirmation).getByText(/did:plc:example/)).toBeTruthy();
  await userEvent.dblClick(
    within(confirmation).getByRole("button", { name: "この内容を公開する" }),
  );
  expect(
    f.operations().filter((op) => op.operation === "autonomousPublish"),
  ).toEqual([
    { operation: "autonomousPublish", id: "draft-one", expectedRevision: 1 },
  ]);
  await act(async () =>
    finish?.(result([draft("draft-one", 1, "最初の本文", "PUBLISHED")])),
  );
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(screen.queryByRole("button", { name: "公開内容を確認" })).toBeNull();
});

it("keeps unknown results read-only and reconciles without another publication", async () => {
  const f = fixture((_server, op) => {
    if (op.operation === "autonomousDraft")
      return result([draft("draft-one", 1, "全文", "UNKNOWN")]);
    if (op.operation === "autonomousReconcile")
      return result([draft("draft-one", 1, "全文", "PUBLISHED")]);
    return undefined;
  });
  render(<AutonomousDrafts call={f.call as Command} serverId="s" />);
  await openDraft();
  expect(screen.queryByRole("button", { name: "公開内容を確認" })).toBeNull();
  await userEvent.click(screen.getByRole("button", { name: "公開結果を照合" }));
  expect(f.operations()).toContainEqual({
    operation: "autonomousReconcile",
    id: "draft-one",
    expectedRevision: 1,
  });
  expect(
    f.operations().some((op) => op.operation === "autonomousPublish"),
  ).toBe(false);
});

it("cancels publication confirmation and requires reviewing the saved edited body", async () => {
  const f = fixture();
  render(<AutonomousDrafts call={f.call as Command} serverId="s" />);
  await openDraft();
  await userEvent.click(screen.getByRole("button", { name: "公開内容を確認" }));
  await userEvent.click(screen.getByRole("button", { name: "公開を取り消す" }));
  expect(screen.queryByRole("dialog")).toBeNull();
  await userEvent.click(screen.getByRole("button", { name: "本文を編集" }));
  expect(
    (
      screen.getByRole("button", {
        name: "公開内容を確認",
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  await userEvent.click(screen.getByRole("button", { name: "編集を保存" }));
  await userEvent.click(screen.getByRole("button", { name: "公開内容を確認" }));
  expect(screen.getByLabelText("公開する本文の全文").textContent).toBe(
    "サーバーから返された全文",
  );
  expect(within(screen.getByRole("dialog")).getByText(/改訂: 2/)).toBeTruthy();
  expect(
    f.operations().some((op) => op.operation === "autonomousPublish"),
  ).toBe(false);
});

it("requires an explicit full-content confirmation before retrying a known unsent failure", async () => {
  const f = fixture((_server, op) => {
    if (op.operation === "autonomousDraft")
      return result([draft("draft-one", 3, "凍結した本文", "FAILED")]);
    if (op.operation === "autonomousPublish")
      return result([draft("draft-one", 3, "凍結した本文", "PUBLISHED")]);
    return undefined;
  });
  render(<AutonomousDrafts call={f.call as Command} serverId="s" />);
  await openDraft();
  await userEvent.click(
    screen.getByRole("button", { name: "同じ内容で再試行を確認" }),
  );
  expect(screen.getByLabelText("公開する本文の全文").textContent).toBe(
    "凍結した本文",
  );
  expect(
    f.operations().some((op) => op.operation === "autonomousPublish"),
  ).toBe(false);
  await userEvent.click(
    screen.getByRole("button", { name: "この内容を公開する" }),
  );
  expect(f.operations()).toContainEqual({
    operation: "autonomousPublish",
    id: "draft-one",
    expectedRevision: 3,
  });
});

it("ignores a late publication result after switching servers", async () => {
  let finish: ((value: WorkspaceResult) => void) | undefined;
  const f = fixture((server, op) => {
    if (server === "a" && op.operation === "autonomousPublish")
      return new Promise((resolve) => {
        finish = resolve;
      });
    if (server === "b" && op.operation === "autonomousDraft")
      return result([draft("draft-one", 1, "新しいサーバーの本文")]);
    return undefined;
  });
  const view = render(
    <AutonomousDrafts call={f.call as Command} serverId="a" />,
  );
  await openDraft();
  await userEvent.click(screen.getByRole("button", { name: "公開内容を確認" }));
  await userEvent.click(
    screen.getByRole("button", { name: "この内容を公開する" }),
  );
  view.rerender(<AutonomousDrafts call={f.call as Command} serverId="b" />);
  await openDraft();
  await act(async () =>
    finish?.(
      result([draft("draft-one", 1, "OLD_SERVER_PRIVATE", "PUBLISHED")]),
    ),
  );
  expect(screen.queryByText("OLD_SERVER_PRIVATE")).toBeNull();
  expect(screen.getByLabelText("投稿本文の全文").textContent).toBe(
    "新しいサーバーの本文",
  );
  expect(screen.queryByRole("dialog")).toBeNull();
});

it("requires refreshing after a disconnected publication instead of offering an immediate retry", async () => {
  const f = fixture((_server, op) => {
    if (op.operation === "autonomousPublish")
      throw new Error("Request timeout");
    return undefined;
  });
  render(<AutonomousDrafts call={f.call as Command} serverId="s" />);
  await openDraft();
  await userEvent.click(screen.getByRole("button", { name: "公開内容を確認" }));
  await userEvent.click(
    screen.getByRole("button", { name: "この内容を公開する" }),
  );
  await screen.findByText(/公開結果を確認できませんでした/);
  expect(
    (
      screen.getByRole("button", {
        name: "公開内容を確認",
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(
    f.operations().filter((op) => op.operation === "autonomousPublish"),
  ).toHaveLength(1);
});
type Item = WorkspaceResult["items"][number];
const result = (items: Item[]): WorkspaceResult => ({ title: "結果", items });
const draft = (
  id = "draft-one",
  revision = 1,
  text = "最初の本文",
  status = "PENDING_REVIEW",
): Item => ({
  id,
  title: "投稿案",
  fields: [
    ["Type", "AutonomousDraft"],
    ["Text", text],
    ["Revision", String(revision)],
    ["Status", status],
    ["Account", "rei.example"],
    ["DID", "did:plc:example"],
    ["Reason", "投稿理由"],
    ["SourceIds", "topic:source-one:1"],
    ["CreatedAt", "2026-11-28T03:00:00Z"],
    ["ExpiresAt", "2026-12-01T03:00:00Z"],
    ["Trigger", "MANUAL"],
    ["RegenerationAttempts", "0"],
  ],
});
const source = (
  revision = 1,
  allowed = false,
  text = "公開用の話題",
): Item => ({
  id: "source-one",
  title: "話題",
  fields: [
    ["Type", "AutonomousSource"],
    ["Text", text],
    ["Revision", String(revision)],
    ["PublicUseAllowed", String(allowed)],
    ["UpdatedAt", "2026-11-28T03:00:00Z"],
  ],
});
const generation = (
  status: string,
  reason: string,
  item?: Item,
): WorkspaceResult =>
  result([
    {
      id: null,
      title: "生成結果",
      fields: [
        ["Type", "AutonomousGeneration"],
        ["GenerationStatus", status],
        ["GenerationReason", reason],
      ],
    },
    ...(item ? [item] : []),
  ]);
function fixture(
  override?: (
    server: string,
    operation: WorkspaceOperation,
  ) => WorkspaceResult | Promise<WorkspaceResult> | undefined,
  initiallyAllowed = false,
) {
  let current = draft();
  let topic = source(1, initiallyAllowed);
  const call = vi
    .fn()
    .mockImplementation(
      async (
        _name: string,
        args: { serverId: string; operation: WorkspaceOperation },
      ) => {
        const op = args.operation;
        const custom = override?.(args.serverId, op);
        if (custom !== undefined) return custom;
        switch (op.operation) {
          case "autonomousDrafts":
            return result([current]);
          case "autonomousSources":
            return result([topic]);
          case "autonomousDraft":
            return result([current]);
          case "autonomousSource":
            return result([topic]);
          case "autonomousEdit":
            current = draft(
              "draft-one",
              op.expectedRevision + 1,
              "サーバーから返された全文",
            );
            return result([current]);
          case "autonomousReject":
            current = draft(
              "draft-one",
              op.expectedRevision,
              "最初の本文",
              "REJECTED",
            );
            return result([current]);
          case "autonomousEditSource":
            topic = source(op.expectedRevision + 1, false, op.text);
            return result([topic]);
          case "autonomousPermitSource":
            topic = source(
              op.expectedRevision + 1,
              true,
              topic.fields.find(([k]) => k === "Text")?.[1],
            );
            return result([topic]);
          case "autonomousRevokeSource":
            topic = source(op.expectedRevision + 1, false);
            return result([topic]);
          case "autonomousAddSource":
            topic = source(1, op.publicUseAllowed, op.text);
            return result([topic]);
          default:
            throw new Error(`Unexpected operation: ${op.operation}`);
        }
      },
    );
  return {
    call,
    operations: () =>
      call.mock.calls.map(([, args]) => args.operation as WorkspaceOperation),
  };
}
async function openDraft() {
  await userEvent.click(
    await screen.findByRole("button", { name: "投稿案を確認 draft-one" }),
  );
  return screen.findByRole("region", { name: "投稿案の詳細" });
}

it("reviews full content and displays the server's new revision after editing", async () => {
  const f = fixture();
  render(<AutonomousDrafts call={f.call as Command} serverId="s" />);
  const detail = await openDraft();
  expect(within(detail).getByLabelText("投稿本文の全文").textContent).toBe(
    "最初の本文",
  );
  expect(within(detail).getByText("rei.example")).toBeTruthy();
  expect(within(detail).getByText("topic:source-one:1")).toBeTruthy();
  expect(
    screen.queryByRole("button", { name: /承認して投稿|投稿する/ }),
  ).toBeNull();
  await userEvent.click(
    within(detail).getByRole("button", { name: "本文を編集" }),
  );
  await userEvent.clear(screen.getByLabelText("投稿本文"));
  await userEvent.type(screen.getByLabelText("投稿本文"), "入力した本文");
  await userEvent.click(screen.getByRole("button", { name: "編集を保存" }));
  await waitFor(() =>
    expect(screen.getByLabelText("投稿本文の全文").textContent).toBe(
      "サーバーから返された全文",
    ),
  );
  expect(f.operations()).toContainEqual({
    operation: "autonomousEdit",
    id: "draft-one",
    expectedRevision: 1,
    text: "入力した本文",
  });
});

it("suppresses duplicate regeneration and keeps the current body visible on failure", async () => {
  let release: ((value: WorkspaceResult) => void) | undefined;
  const f = fixture((_server, op) =>
    op.operation === "autonomousRegenerate"
      ? new Promise((resolve) => {
          release = resolve;
        })
      : undefined,
  );
  render(<AutonomousDrafts call={f.call as Command} serverId="s" />);
  await openDraft();
  await userEvent.dblClick(screen.getByRole("button", { name: "再生成" }));
  expect(
    f.operations().filter((op) => op.operation === "autonomousRegenerate"),
  ).toHaveLength(1);
  await act(async () =>
    release?.(generation("FAILED", "generation_failed", draft())),
  );
  expect(await screen.findByRole("alert")).toBeTruthy();
  expect(screen.getByLabelText("投稿本文の全文").textContent).toBe(
    "最初の本文",
  );
});

it("holding only closes review and rejection sends an optional private reason", async () => {
  const f = fixture();
  render(<AutonomousDrafts call={f.call as Command} serverId="s" />);
  await openDraft();
  const before = f.operations().length;
  await userEvent.click(screen.getByRole("button", { name: "保留して閉じる" }));
  expect(screen.queryByRole("region", { name: "投稿案の詳細" })).toBeNull();
  expect(f.operations()).toHaveLength(before);
  await openDraft();
  await userEvent.type(
    screen.getByLabelText("見送り理由（非公開・任意）"),
    "PRIVATE_REASON",
  );
  await userEvent.click(screen.getByRole("button", { name: "見送る" }));
  await waitFor(() =>
    expect(f.operations()).toContainEqual({
      operation: "autonomousReject",
      id: "draft-one",
      expectedRevision: 1,
      reason: "PRIVATE_REASON",
    }),
  );
  expect(screen.queryByRole("button", { name: "再生成" })).toBeNull();
});

it("requires saving topic edits before changing permission and uses the new revision", async () => {
  const f = fixture(undefined, true);
  render(<AutonomousDrafts call={f.call as Command} serverId="s" />);
  await userEvent.click(
    await screen.findByRole("button", { name: "公開用の話題を管理" }),
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "話題を確認 source-one" }),
  );
  await userEvent.clear(screen.getByLabelText("話題の本文"));
  await userEvent.type(screen.getByLabelText("話題の本文"), "編集した話題");
  expect(
    (
      screen.getByRole("button", {
        name: "生成への利用を取り消す",
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  await userEvent.click(
    screen.getByRole("button", { name: "話題の変更を保存" }),
  );
  const permit = await screen.findByRole("button", {
    name: "生成への利用を許可",
  });
  await waitFor(() =>
    expect((permit as HTMLButtonElement).disabled).toBe(false),
  );
  await userEvent.click(permit);
  expect(f.operations()).toContainEqual({
    operation: "autonomousPermitSource",
    id: "source-one",
    expectedRevision: 2,
  });
});

it("new topics default to no generation permission", async () => {
  const f = fixture();
  render(<AutonomousDrafts call={f.call as Command} serverId="s" />);
  await userEvent.click(
    await screen.findByRole("button", { name: "公開用の話題を管理" }),
  );
  expect(
    (screen.getByLabelText("投稿案の生成に利用してよい") as HTMLInputElement)
      .checked,
  ).toBe(false);
  await userEvent.type(screen.getByLabelText("新しい話題の本文"), "新しい話題");
  await userEvent.click(screen.getByRole("button", { name: "話題を登録" }));
  expect(f.operations()).toContainEqual({
    operation: "autonomousAddSource",
    text: "新しい話題",
    publicUseAllowed: false,
  });
});

it("ignores late review responses after switching servers", async () => {
  let release: ((value: WorkspaceResult) => void) | undefined;
  const f = fixture((server, op) => {
    if (op.operation === "autonomousDrafts")
      return result([
        draft(server === "a" ? "draft-one" : "draft-b", 1, `server ${server}`),
      ]);
    if (server === "a" && op.operation === "autonomousDraft")
      return new Promise((resolve) => {
        release = resolve;
      });
    return undefined;
  });
  const view = render(
    <AutonomousDrafts call={f.call as Command} serverId="a" />,
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "投稿案を確認 draft-one" }),
  );
  view.rerender(<AutonomousDrafts call={f.call as Command} serverId="b" />);
  await screen.findByRole("button", { name: "投稿案を確認 draft-b" });
  await act(async () =>
    release?.(result([draft("draft-one", 1, "OLD_SERVER_SECRET")])),
  );
  expect(screen.queryByText("OLD_SERVER_SECRET")).toBeNull();
});

it("renders model text literally instead of creating an approval control", async () => {
  const text = "<button>承認して投稿</button>";
  const f = fixture((_server, op) =>
    op.operation === "autonomousDraft"
      ? result([draft("draft-one", 1, text)])
      : undefined,
  );
  render(<AutonomousDrafts call={f.call as Command} serverId="s" />);
  await openDraft();
  expect(screen.getByLabelText("投稿本文の全文").textContent).toBe(text);
  expect(screen.queryByRole("button", { name: "承認して投稿" })).toBeNull();
});
