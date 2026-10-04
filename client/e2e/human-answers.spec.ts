import { test, expect } from "@playwright/test";
test("human answer is reviewed explicitly and saved without starting another run", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await page.getByRole("button", { name: "復旧・再開", exact: true }).click();
  const panel = page.getByRole("region", { name: "人間回答待ち", exact: true });
  await expect(
    panel.getByText("Fixture question: choose A or B"),
  ).toBeVisible();
  await panel.getByRole("textbox", { name: "回答" }).fill("Choice A");
  await panel.getByRole("button", { name: "回答内容を確認" }).click();
  const confirm = panel.getByRole("button", { name: "この回答を保存" });
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
    path: `test-results/${info.project.name}-human-answers.png`,
  });
  await confirm.click();
  await expect(panel.getByText("保存済み回答: Choice A")).toBeVisible();
  await expect(panel.getByText(/回答を保存しました/)).toBeVisible();
  await page.getByRole("button", { name: /Active Runs/ }).click();
  await expect(page.getByText("checkpoint-run", { exact: true })).toHaveCount(
    0,
  );
});
