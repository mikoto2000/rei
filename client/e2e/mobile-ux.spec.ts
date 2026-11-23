import { test, expect } from "@playwright/test";

test("mobile navigation leaves the composer usable in both send modes", async ({
  page,
}, info) => {
  test.skip(info.project.name !== "mobile");
  await page.goto("/e2e/fixture.html");
  await page.locator(".conversation-card > button:first-child").click();
  await page.locator(".composer summary").click();
  for (const mode of ["EXCLUSIVE", "INTERVENTION"]) {
    await page.locator("#run-mode").selectOption(mode);
    if (mode === "INTERVENTION")
      await page.locator("#input-run").selectOption("r");
    await page.locator("#message").fill("追加の依頼");
    await page.locator("#message").scrollIntoViewIfNeeded();
    const sidebar = await page.locator(".sidebar").boundingBox();
    const send = await page.locator(".composer-footer button").boundingBox();
    expect(send!.y + send!.height).toBeLessThanOrEqual(sidebar!.y);
    await page.locator(".composer-footer button").click({ trial: true });
  }
  await page.getByRole("button", { name: "その他", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Inbox・承認", exact: true }),
  ).toBeVisible();
});
