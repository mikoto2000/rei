import { test, expect } from "@playwright/test";
test("selects the next Run endpoint and model while keeping the current Run visible", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html?llm=1");
  await page.locator(".conversation-card > button:first-child").click();
  const endpoint = page.getByRole("combobox", { name: "LLM接続先" });
  await expect(endpoint).toHaveValue("local");
  await endpoint.selectOption("plan");
  await expect(endpoint).toHaveValue("plan");
  await expect(
    page.getByText(/利用経路（設定による申告）: chatgpt-plan/),
  ).toBeVisible();
  await expect(page.getByText(/アカウント: personal/)).toBeVisible();
  await page.getByRole("button", { name: "モデル一覧を取得" }).click();
  const model = page.getByRole("combobox", { name: "LLMモデル" });
  await expect(model).toHaveValue("plan-model");
  await model.selectOption("plan-second");
  await expect(model).toHaveValue("plan-second");
  await page.getByText("接続先の詳細", { exact: true }).click();
  await expect(page.getByText(/実サービスでの推論動作は未検証/)).toBeVisible();
  await expect(page.locator(".llm-controls")).toContainText("UNKNOWN");
  await expect(page.locator(".transcript")).toContainText(
    "API の構造を確認しています",
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `test-results/${info.project.name}-llm-controls.png`,
    fullPage: true,
  });
});
