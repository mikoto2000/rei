import { test, expect } from "@playwright/test";
test("Inbox reviews notifications and explicitly confirms one-time approval", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await page.getByRole("button", { name: "Inbox・承認", exact: true }).click();
  await expect(page.getByText("Fixture failure requires review")).toBeVisible();
  await page.getByRole("button", { name: "通知を確認済みにする" }).click();
  await expect(page.getByText("未確認の通知はありません。")).toBeVisible();
  await page.getByRole("button", { name: "承認内容を確認" }).click();
  await expect(
    page.getByRole("heading", {
      name: "このTool呼び出しを一回だけ承認しますか？",
    }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  const confirm = page.getByRole("button", { name: "一回だけ承認" });
  await confirm.scrollIntoViewIfNeeded();
  if (info.project.name === "mobile") {
    const button = await confirm.boundingBox();
    const nav = await page.locator(".sidebar").boundingBox();
    expect(button!.y + button!.height).toBeLessThanOrEqual(nav!.y);
  }
  await page.screenshot({
    path: `test-results/${info.project.name}-inbox.png`,
    fullPage: false,
  });
  await page.getByRole("button", { name: "一回だけ承認" }).click();
  await expect(page.getByText("有効な承認要求はありません。")).toBeVisible();
  await expect(
    page.getByText(
      "承認は一回の呼び出しに対する判断です。通知の確認や承認だけでは実行を再開しません。",
    ),
  ).toBeVisible();
});
