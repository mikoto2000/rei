import { test, expect } from "@playwright/test";

test("user icon persists, appears in history and live chat, and resets", async ({
  page,
}, info) => {
  // Includes three page loads and decoding/persisting a full-size image.
  test.setTimeout(60_000);
  await page.goto("/e2e/fixture.html");
  await page.getByRole("button", { name: "⚙ Settings" }).click();
  const input = page.getByLabel("アイコン画像を選択");
  await input.setInputFiles("public/rei-avatar.png");
  await expect(page.locator(".avatar-settings img")).toHaveAttribute(
    "src",
    /^data:image\/png;base64,/,
  );
  await input.setInputFiles({
    name: "broken.png",
    mimeType: "image/png",
    buffer: Buffer.from("not an image"),
  });
  await expect(page.getByRole("alert")).toContainText("有効な画像");
  await expect(page.locator(".avatar-settings img")).toBeVisible();
  await page.reload();
  await page.locator(".conversation-card button").click();
  const users = page.locator(".message.user img");
  await expect(users).toHaveCount(2);
  await expect(users.first()).toHaveAttribute(
    "src",
    /^data:image\/png;base64,/,
  );
  for (const image of await page.locator(".message img").all()) {
    await expect(image).toBeVisible();
    await expect
      .poll(() =>
        image.evaluate((node) => (node as HTMLImageElement).naturalWidth),
      )
      .toBeGreaterThan(0);
  }
  await page.screenshot({
    path: `test-results/${info.project.name}-avatars-chat.png`,
    fullPage: true,
  });
  await page.getByRole("button", { name: "⚙ Settings" }).click();
  await page.screenshot({
    path: `test-results/${info.project.name}-avatars-settings.png`,
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.getByRole("button", { name: "標準に戻す" }).click();
  await page.reload();
  await page.getByRole("button", { name: "⚙ Settings" }).click();
  await expect(page.locator(".avatar-settings img")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "標準に戻す" })).toBeDisabled();
});
