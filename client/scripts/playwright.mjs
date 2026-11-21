import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

// Playwright forces color in workers. Resolve the competing environment setting
// before starting it, keeping the parent's no-color preference and worker output.
const env = { ...process.env };
if (env.NO_COLOR !== undefined) {
  env.FORCE_COLOR ??= "0";
  delete env.NO_COLOR;
}
const cli = fileURLToPath(import.meta.resolve("@playwright/test/cli"));
const child = spawn(process.execPath, [cli, "test", ...process.argv.slice(2)], {
  env,
  stdio: "inherit",
});
child.on("error", (error) => {
  console.error(error);
  process.exitCode = 1;
});
child.on("exit", (code, signal) => {
  if (signal) process.kill(process.pid, signal);
  else process.exitCode = code ?? 1;
});
