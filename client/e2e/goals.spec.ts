import { test, expect } from "@playwright/test";
test("Goal explicit execution reviews budgets and attaches its actual Run", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await page.getByRole("button", { name: "復旧・再開", exact: true }).click();
  const panel = page.getByRole("region", { name: "Goalの管理", exact: true });
  await panel.getByRole("button", { name: "Goalを確認" }).click();
  await expect(panel.getByText("saved_attempt_history")).toBeVisible();
  await panel.getByRole("button", { name: "Goalの実行内容を確認" }).click();
  const confirm = panel.getByRole("button", { name: "このGoal操作を実行" });
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
    path: `test-results/${info.project.name}-goals.png`,
  });
  await confirm.click();
  await expect(panel.getByText("接続したGoal Run: goal-run")).toBeVisible();
});
test("uncertain Goal reconciliation needs acknowledgement and never dispatches a replacement Run", async ({
  page,
}) => {
  await page.goto("/e2e/fixture.html?goal=uncertain");
  await page.getByRole("button", { name: "復旧・再開", exact: true }).click();
  const panel = page.getByRole("region", { name: "Goalの管理", exact: true });
  await panel.getByRole("button", { name: "Goalを確認" }).click();
  await panel
    .getByRole("button", { name: "不確定Runの照合内容を確認" })
    .click();
  const confirm = panel.getByRole("button", { name: "このGoal操作を実行" });
  await expect(confirm).toBeDisabled();
  await panel
    .getByRole("checkbox", { name: /現在の成果物と履歴を確認/ })
    .check();
  await confirm.click();
  await expect(panel.getByText("uncertain_run_reconciled")).toBeVisible();
  await expect(panel.getByText(/接続したGoal Run/)).toHaveCount(0);
});
