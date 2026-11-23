import { test, expect } from "@playwright/test";

test("draft survives settings and a running task receives the selected guidance target", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await page.locator(".conversation-card > button:first-child").click();
  await page.getByLabel("メッセージ", { exact: true }).fill("未送信の依頼");
  await page.locator(".composer summary").click();
  await page.locator("#run-mode").selectOption("READ_ONLY");
  await page.getByRole("button", { name: /⚙ 設定/ }).click();
  await page.getByRole("button", { name: /▤ 会話/ }).click();
  await page.locator(".conversation-card > button:first-child").click();
  await expect(page.getByLabel("メッセージ", { exact: true })).toHaveValue(
    "未送信の依頼",
  );
  await expect(page.locator("#run-mode")).toHaveValue("READ_ONLY");
  await page.getByRole("button", { name: "この作業に追加指示" }).click();
  await expect(page.getByLabel("メッセージ", { exact: true })).toBeFocused();
  await expect(page.locator("#run-mode")).toHaveValue("INTERVENTION");
  await expect(page.locator("#input-run")).toHaveValue("r");
  await page.getByLabel("メッセージ", { exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({
    path: `test-results/${info.project.name}-ux-composer.png`,
  });
});

test("history search, latest preview, pin and archive survive reload", async ({
  page,
}) => {
  await page.goto("/e2e/fixture.html");
  await page.getByLabel("会話を検索", { exact: true }).fill("見つからない会話");
  await expect(page.locator(".conversation-card")).toHaveCount(0);
  await page.getByLabel("会話を検索", { exact: true }).fill("Web API");
  await page.getByRole("button", { name: "最新の発言を確認" }).click();
  await expect(page.locator(".session-preview")).toContainText(
    "保存済みの回答",
  );
  await page.getByRole("button", { name: "ピン留め", exact: true }).click();
  await page.getByRole("button", { name: "アーカイブ", exact: true }).click();
  await expect(page.locator(".conversation-card")).toHaveCount(0);
  await page.reload();
  await expect(page.locator(".conversation-card")).toHaveCount(0);
  await page.getByLabel("アーカイブした会話を表示").check();
  await expect(
    page.getByRole("button", { name: "ピン留めを解除" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "アーカイブから戻す" }).click();
  await page.getByLabel("アーカイブした会話を表示").uncheck();
  await expect(page.locator(".conversation-card")).toHaveCount(1);
});
