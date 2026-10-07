import { test, expect } from "@playwright/test";
test("a bounded PNG artifact decodes in the browser preview", async ({
  page,
}) => {
  await page.goto("/e2e/fixture.html?artifact=image");
  await page.getByRole("button", { name: "Artifact", exact: true }).click();
  await page.getByRole("button", { name: "preview", exact: true }).click();
  const image = page.getByRole("img", { name: "生成画像.png" });
  await expect(image).toBeVisible();
  await expect
    .poll(() => image.evaluate((node: HTMLImageElement) => node.naturalWidth))
    .toBe(1);
});
test("conversation Run results open the server artifact list", async ({
  page,
}) => {
  await page.goto("/e2e/fixture.html");
  await page.locator(".conversation-card button").click();
  await page
    .getByRole("button", { name: "このRunの生成物", exact: true })
    .first()
    .click();
  await expect(page.getByText("生成結果.txt", { exact: true })).toBeVisible();
  await expect(page.getByText(/^Run:/)).toBeVisible();
});
test("Task result opens its artifact and explicit preview/save fit the viewport", async ({
  page,
}, info) => {
  await page.goto("/e2e/fixture.html");
  await page.getByRole("button", { name: "Task Manager", exact: true }).click();
  await page
    .getByRole("button", { name: "Artifactを開く", exact: true })
    .click();
  await expect(page.getByText("生成結果.txt", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "preview", exact: true }).click();
  await expect(
    page.getByText("<script>内容は実行せず表示します</script>", {
      exact: true,
    }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Downloadsへ保存" }).click();
  await expect(page.getByRole("status")).toContainText(
    "Downloads/Rei/生成結果.txt",
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: `test-results/${info.project.name}-artifacts.png`,
  });
});
