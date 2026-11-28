import type {
  WorkspaceOperation,
  WorkspaceResult,
} from "../src/entities/workspace";
type Item = WorkspaceResult["items"][number];
let revision = 1;
let text =
  "公開用に登録した話題から作った投稿案です。\n本文の全文を確認してください。";
let state = "PENDING_REVIEW";
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
    default:
      throw "InvalidInput";
  }
  return { title: "投稿案", items: [item()] };
}
