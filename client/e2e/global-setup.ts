import { chromium, type FullConfig } from "@playwright/test";

// Vite's first fixture request transforms the module graph. Finish startup before
// spending the functional tests' time budgets on navigation and UI assertions.
export default async function globalSetup(config: FullConfig) {
  const { baseURL, channel } = config.projects[0].use;
  if (!baseURL) throw new Error("The UI tests require a fixture baseURL");
  const browser = await chromium.launch({ channel, headless: true });
  try {
    const page = await browser.newPage();
    await page.goto(new URL("/e2e/fixture.html", baseURL).href, {
      timeout: 120_000,
    });
    await page.getByRole("button", { name: "Artifact", exact: true }).waitFor({
      state: "visible",
      timeout: 30_000,
    });
  } finally {
    await browser.close();
  }
}
