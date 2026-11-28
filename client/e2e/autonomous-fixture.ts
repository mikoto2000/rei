import type {
  WorkspaceOperation,
  WorkspaceResult,
} from "../src/entities/workspace";
type Item = WorkspaceResult["items"][number];
let revision = 1;
let text =
  "公開用に登録した話題から作った投稿案です。\n本文の全文を確認してください。";
let state = "PENDING_REVIEW";
let noticeRead = false;
const item = (): Item => ({
  id: "draft-one",
  title: "投稿案",
  fields: [
    ["Type", "AutonomousDraft"],
    ["Text", text],
    ["Revision", String(revision)],
    ["Status", state],
    ["Account", "rei.example"],
    ["DID", "did:plc:fixture"],
    ["Reason", "公開話題の共有"],
    ["SourceIds", "topic:source-one:1"],
    ["CreatedAt", "2026-11-28T03:00:00Z"],
    ["UpdatedAt", "2026-11-28T03:00:00Z"],
    ["ExpiresAt", "2026-12-01T03:00:00Z"],
    ["Trigger", "MANUAL"],
    ["RegenerationAttempts", "0"],
  ],
});
export function autonomousFixture(op: WorkspaceOperation): WorkspaceResult {
  switch (op.operation) {
    case "autonomousNotifications":
      return {
        title: "投稿案の通知",
        items:
          noticeRead || state !== "PENDING_REVIEW"
            ? []
            : [
                {
                  id: "a8203063-d97c-4e26-a601-745217152952",
                  title: "投稿案の確認待ち",
                  fields: [
                    ["Type", "AutonomousNotice"],
                    ["DraftId", "draft-one"],
                    ["Status", "OPEN"],
                    ["CreatedAt", "2026-11-28T03:00:00Z"],
                  ],
                },
              ],
      };
    case "autonomousNoticeAck":
      if (op.id !== "a8203063-d97c-4e26-a601-745217152952")
        throw "InvalidInput";
      noticeRead = true;
      return { title: "既読", items: [] };
    case "autonomousSources":
      return { title: "話題", items: [] };
    case "autonomousDrafts":
    case "autonomousDraft":
      break;
    case "autonomousEdit":
      if (op.expectedRevision !== revision || state !== "PENDING_REVIEW")
        throw "ResourceConflict";
      revision++;
      text = op.text;
      break;
    case "autonomousReject":
      if (op.expectedRevision !== revision) throw "ResourceConflict";
      state = "REJECTED";
      break;
    case "autonomousRegenerate":
      if (op.expectedRevision !== revision) throw "ResourceConflict";
      revision++;
      text = "再生成された本文の全文です。\n変更された内容を確認してください。";
      return {
        title: "再生成結果",
        items: [
          {
            id: null,
            title: "生成結果",
            fields: [
              ["Type", "AutonomousGeneration"],
              ["GenerationStatus", "DRAFT_REGENERATED"],
              ["GenerationReason", "generated"],
            ],
          },
          item(),
        ],
      };
    case "autonomousPublish":
      if (op.expectedRevision !== revision || state !== "PENDING_REVIEW")
        throw "ResourceConflict";
      state = new URLSearchParams(window.location.search).has(
        "unknown-publication",
      )
        ? "UNKNOWN"
        : "PUBLISHED";
      break;
    case "autonomousReconcile":
      if (op.expectedRevision !== revision || state !== "UNKNOWN")
        throw "ResourceConflict";
      state = "PUBLISHED";
      break;
    default:
      throw "InvalidInput";
  }
  return { title: "投稿案", items: [item()] };
}
