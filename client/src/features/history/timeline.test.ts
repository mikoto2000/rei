import { expect, it } from "vitest";
import { timeline } from "./timeline";
import type { ConversationTurn, Run } from "../../entities/models";
it("keeps server ordering and merges live projections by run identity", () => {
  const turns = [{ runId: "a" }, { runId: "b" }] as ConversationTurn[];
  const runs = [{ runId: "a" }, { runId: "c" }] as Run[];
  expect(
    timeline(turns, runs).map((row) => [row.turn?.runId, row.run?.runId]),
  ).toEqual([
    ["a", "a"],
    ["b", undefined],
    [undefined, "c"],
  ]);
});
