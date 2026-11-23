import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { afterEach, it, expect, vi } from "vitest";
import { Answer } from "./Answer";
import { openUrl } from "@tauri-apps/plugin-opener";
vi.mock("@tauri-apps/api/core", () => ({ isTauri: () => true }));
vi.mock("@tauri-apps/plugin-opener", () => ({
  openUrl: vi.fn().mockResolvedValue(undefined),
}));
afterEach(cleanup);
it("opens web links with the native browser opener", () => {
  render(<Answer text="[資料](https://example.com)" />);
  fireEvent.click(screen.getByRole("link", { name: "資料" }));
  expect(openUrl).toHaveBeenCalledWith("https://example.com");
});
it("keeps unsafe links and raw HTML inactive", () => {
  const { container } = render(
    <Answer
      text={"[危険](javascript:alert(1))\n\n<script>alert(1)</script>"}
    />,
  );
  expect(container.querySelector('a[href^="javascript:"]')).toBeNull();
  expect(container.querySelector("script")).toBeNull();
});
