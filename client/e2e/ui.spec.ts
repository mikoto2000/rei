import { test, expect } from "@playwright/test";
test("conversation, run selection, explicit cancel and responsive layout", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await page.getByRole("button", { name: /Active Runs/ }).click();
  await page.getByRole("button", { name: /SSE の再接続と認証/ }).click();
  await expect(page.getByText("rei 🔒")).toBeVisible();
  await expect(page.getByText("readFile", { exact: true })).toBeVisible();
  await expect(
    page.getByText("API の構造を確認しています。", { exact: false }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `test-results/${info.project.name}-chat.png`,
    fullPage: true,
  });
  await page.getByRole("button", { name: "Stop", exact: true }).click();
  await expect(page.getByText("CANCELLED", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /Conversations/ }).click();
  await page.getByRole("button", { name: "会話を追加", exact: true }).click();
  await expect(page.getByRole("button", { name: "会話を開始" })).toBeEnabled();
  await page.getByRole("button", { name: "会話を開始" }).click();
  await expect(page.getByPlaceholder("れいに依頼する…")).toBeVisible();
});
test("settings renders connection dimensions separately", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await page.getByRole("button", { name: /Settings/ }).click();
  await page.getByRole("button", { name: "Test Connection" }).click();
  await expect(page.getByText("Server reachable: Yes")).toBeVisible();
  await expect(page.getByText("Authentication OK: Yes")).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `test-results/${info.project.name}-settings.png`,
    fullPage: true,
  });
});
