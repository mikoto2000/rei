import { cleanup, render } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { ActivityRow, ToolRow } from "./RunActivity";

afterEach(cleanup);
it("renders tools and activity as compact shell-like text lines", () => {
  const { container } = render(
    <>
      <ToolRow
        tool={{ id: "c", name: "readFile", status: "RUNNING", summary: "a.rs" }}
      />
      <ToolRow
        tool={{
          id: "c",
          name: "readFile",
          status: "COMPLETED",
          summary: "",
          durationMs: 18,
        }}
      />
      <ActivityRow
        activity={{
          id: "q",
          category: "LLM",
          label: "LLM request",
          status: "RUNNING",
          summary: "chat",
          startedAt: null,
          completedAt: null,
          durationMs: null,
          firstTokenMs: 70,
          error: null,
          metrics: [],
        }}
      />
    </>,
  );
  expect(container.querySelectorAll(".event-line")).toHaveLength(3);
  expect(container.children[0].textContent).toBe("→ readFile a.rs");
  expect(container.children[1].textContent).toBe("✓ readFile 18 ms");
  expect(container.children[2].textContent).toContain("[llm]");
  expect(
    container.querySelector("strong, p, .notice, .activity-heading"),
  ).toBeNull();
});
