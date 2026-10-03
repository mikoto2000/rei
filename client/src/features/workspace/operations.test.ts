import { it, expect } from "vitest";
import { buildOperation, operations } from "./operations";
it("rejects fractional IDs and non-finite counts before invoking native commands", () => {
  expect(() => buildOperation("deleteFeed", { id: "1.5" })).toThrow();
  expect(() => buildOperation("interests", { hours: "NaN" })).toThrow();
  expect(() =>
    buildOperation("search", {
      query: "rei",
      vectorTopK: "2.5",
      webTopK: "5",
      threshold: "0.5",
    }),
  ).toThrow();
  expect(
    buildOperation("search", {
      query: "rei",
      vectorTopK: "3",
      webTopK: "5",
      threshold: "0.5",
    }),
  ).toEqual({
    operation: "search",
    query: "rei",
    vectorTopK: 3,
    webTopK: 5,
    threshold: 0.5,
  });
});
it("offers only server-published operations and accepts reminder zero-minutes", () => {
  for (const forbidden of [
    "shell",
    "updateProfile",
    "createSkill",
    "deleteSkill",
    "updateReminder",
    "updateMemory",
    "deleteInterest",
    "projectAdd",
  ]) {
    expect(operations.some((o) => o.id === forbidden)).toBe(false);
    expect(() => buildOperation(forbidden, {})).toThrow();
  }
  expect(
    buildOperation("createReminder", {
      message: "hello",
      target: "2026-10-04T09:00:00+09:00",
      minutesBefore: "0",
    }),
  ).toEqual({
    operation: "createReminder",
    message: "hello",
    at: null,
    target: "2026-10-04T09:00:00+09:00",
    minutesBefore: 0,
  });
});
