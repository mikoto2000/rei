import { renderHook, act, cleanup } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { readUserAvatar, useUserAvatar } from "./userAvatar";

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.restoreAllMocks();
});

it("rejects unsupported and oversized files", async () => {
  await expect(
    readUserAvatar(new File(["<svg/>"], "icon.svg", { type: "image/svg+xml" })),
  ).rejects.toThrow("PNG");
  await expect(
    readUserAvatar(
      new File([new Uint8Array(2 * 1024 * 1024 + 1)], "icon.png", {
        type: "image/png",
      }),
    ),
  ).rejects.toThrow("2 MB");
});

it("keeps the current icon when persistence fails", () => {
  const { result } = renderHook(useUserAvatar);
  act(() => result.current[1]("data:image/png;base64,AAAA"));
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
    throw new Error("quota");
  });
  expect(() =>
    act(() => result.current[1]("data:image/png;base64,BBBB")),
  ).toThrow("保存できません");
  expect(result.current[0]).toBe("data:image/png;base64,AAAA");
});
