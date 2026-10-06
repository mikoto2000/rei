import { test, expect } from "@playwright/test";
test("Checkpoint inspection and explicit resume retain session and attach run", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await page.getByRole("button", { name: "復旧・再開", exact: true }).click();
  await page.getByRole("button", { name: "状態を照合" }).click();
  await expect(page.getByText("Fixture file changed")).toBeVisible();
  await expect(
    page.getByText("writeFile result requires confirmation"),
  ).toBeVisible();
  await page.getByRole("button", { name: "再開内容を確認" }).click();
  const confirm = page.getByRole("button", { name: "このTaskを再開" });
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
    path: `test-results/${info.project.name}-recovery.png`,
  });
  await confirm.click();
  await expect(page.getByText(/接続したRun: checkpoint-run/)).toBeVisible();
});
