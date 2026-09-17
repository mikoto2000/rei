import { test, expect } from "@playwright/test";
test("server history opens, pages forward and returns to list", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await page.locator(".conversation-card button").click();
  await expect(page.getByText("保存済みの質問", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "次のメッセージを読み込む" }).click();
  await expect(
    page.getByText("次の保存済み質問", { exact: true }),
  ).toBeVisible();
  await page.getByText("Activity · 5", { exact: true }).click();
  await expect(page.getByText("readFile", { exact: true })).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `test-results/${info.project.name}-history.png`,
    fullPage: true,
  });
  await page.getByRole("button", { name: "会話一覧へ戻る" }).click();
  await page
    .getByRole("combobox", { name: "会話のプロジェクト" })
    .selectOption("p");
  await expect(page.locator(".conversation-card")).toHaveCount(1);
});
test("conversation, run selection, explicit cancel and responsive layout", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await page.getByRole("button", { name: /Active Runs/ }).click();
  await page.getByRole("button", { name: /SSE の再接続と認証/ }).click();
  await expect(page.getByText("rei 🔒")).toBeVisible();
  await page.getByText("Activity · 5", { exact: true }).click();
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

test("live activity collapses on desktop and mobile and retains completed updates", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await page.locator(".conversation-card button").click();
  const summary = page.getByText("Activity · 5", { exact: true });
  const activity = page.locator(".live-activity");
  await expect(activity).not.toHaveAttribute("open", "");
  await expect(page.getByText("readFile", { exact: true })).not.toBeVisible();
  await summary.click();
  await expect(activity).toHaveAttribute("open", "");
  await expect(page.getByText("First token: 70 ms")).toBeVisible();
  await expect(page.getByText("No progress: 1")).toBeVisible();
  await expect(page.getByText("coding", { exact: true })).toBeVisible();
  await expect(page.getByText("Old.java", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "状態を更新" }).click();
  await expect(page.getByText("210 ms", { exact: true })).toBeVisible();
  await expect(
    page.getByText("確認が完了しました。", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Stop", exact: true }),
  ).toHaveCount(0);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `test-results/${info.project.name}-live-activity.png`,
    fullPage: true,
  });
  await summary.click();
  await expect(page.getByText("First token: 70 ms")).not.toBeVisible();
  await summary.click();
  await page.getByRole("button", { name: "状態を更新" }).click();
  await expect(page.getByText("Operation failed.")).toBeVisible();
  // Past turns in the same transcript never acquire runtime activity.
  await expect(
    page
      .locator("article.turn")
      .filter({ hasText: "保存済みの質問" })
      .locator(".live-activity"),
  ).toHaveCount(0);
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
