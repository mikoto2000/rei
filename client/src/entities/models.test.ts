import { describe, it, expect } from "vitest";
import { mergeRun, activeRuns, errorText, canSubmit, type Run } from "./models";
const run = (
  id: string,
  status: Run["status"] = "RUNNING",
  revision = 1,
): Run => ({
  serverId: "s",
  runId: id,
  conversationId: "c",
  projectId: "p",
  sessionId: "session",
  turnId: "t",
  prompt: "hello",
  assistantText: "",
  status,
  streamState: "CONNECTED",
  lastSequence: null,
  incomplete: false,
  error: null,
  failure: null,
  tools: [],
  activities: [],
  messages: [],
  workingSet: [],
  revision,
});
describe("Run view model", () => {
  it("keeps concurrent runs and removes terminal runs from active list", () => {
    const runs = [run("a"), run("b", "QUEUED"), run("c", "COMPLETED")];
    expect(activeRuns(runs).map((r) => r.runId)).toEqual(["a", "b"]);
  });
  it("does not let an older snapshot overwrite an event", () => {
    const current = run("a", "COMPLETED", 3);
    expect(mergeRun([current], run("a", "RUNNING", 2))).toEqual([current]);
  });
  it("keys runs by server and run id", () => {
    expect(
      mergeRun([run("a")], { ...run("a"), serverId: "other" }),
    ).toHaveLength(2);
  });
  it("allows another conversation while one is running", () => {
    expect(canSubmit("other", "hi", [run("a")], false)).toBe(true);
    expect(canSubmit("c", "hi", [run("a")], false)).toBe(false);
  });
  it("rejects empty messages and pending sends", () => {
    expect(canSubmit("c", " ", [], false)).toBe(false);
    expect(canSubmit("c", "hi", [], true)).toBe(false);
  });
  it("explains session expiry without leaking raw errors", () => {
    expect(errorText("SessionNotFound")).toContain("セッション");
    expect(errorText(new Error("api-secret"))).not.toContain("api-secret");
  });
});
