import { test, expect } from "@playwright/test";
test("schedule activation reviews timing and attaches its actual Run", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await page.getByRole("button", { name: "復旧・再開", exact: true }).click();
  const panel = page.getByRole("region", { name: "予約の管理", exact: true });
  await panel.getByRole("button", { name: "予約を確認" }).click();
  await expect(panel.getByText("saved_schedule_history")).toBeVisible();
  await panel.getByRole("button", { name: "予約の有効化内容を確認" }).click();
  const confirm = panel.getByRole("button", { name: "この予約操作を実行" });
  await confirm.scrollIntoViewIfNeeded();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  if (info.project.name === "mobile") {
    const button = await confirm.boundingBox();
    const nav = await page.locator(".sidebar").boundingBox();
    expect(button!.y + button!.height).toBeLessThanOrEqual(nav!.y);
  }
  await page.screenshot({
    path: `test-results/${info.project.name}-schedules.png`,
  });
  await confirm.click();
  await expect(panel.getByText("接続した予約 Run: schedule-run")).toBeVisible();
});
test("uncertain schedule reconciliation requires acknowledgement and never starts a replacement", async ({
  page,
}) => {
  await page.goto("/e2e/fixture.html?schedule=uncertain");
  await page.getByRole("button", { name: "復旧・再開", exact: true }).click();
  const panel = page.getByRole("region", { name: "予約の管理", exact: true });
  await panel.getByRole("button", { name: "予約を確認" }).click();
  await panel
    .getByRole("button", { name: "不確定予約の照合内容を確認" })
    .click();
  const confirm = panel.getByRole("button", { name: "この予約操作を実行" });
  await expect(confirm).toBeDisabled();
  await panel.getByRole("checkbox").check();
  await confirm.click();
  await expect(panel.getByText("uncertain_run_reconciled")).toBeVisible();
  await expect(panel.getByText(/接続した予約 Run/)).toHaveCount(0);
});
