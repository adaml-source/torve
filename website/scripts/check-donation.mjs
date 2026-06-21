import { spawnSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const env = {
  ...process.env,
  TORVE_DONATION_URL: "https://example.invalid/donate"
};

for (const args of [
  ["scripts/build-site.mjs", "--out", "dist-donation-test"],
  ["scripts/validate-site.mjs", "--root", "dist-donation-test", "--expect-donation"]
]) {
  const result = spawnSync(process.execPath, args, {
    cwd: root,
    env,
    stdio: "inherit"
  });

  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}
