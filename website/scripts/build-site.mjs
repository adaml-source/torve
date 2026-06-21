import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const source = join(root, "src");
const publicDir = join(root, "public");
const outFlagIndex = process.argv.indexOf("--out");
const outDir = outFlagIndex >= 0 ? process.argv[outFlagIndex + 1] : "dist";
const dist = join(root, outDir);
const donationUrl = process.env.TORVE_DONATION_URL || "";

const files = [
  "styles.css",
  "site.js",
  "index.html",
  "download.html",
  "source.html",
  "signin.html",
  "signup.html",
  "account.html",
  "privacy.html",
  "terms.html",
  "account-deletion.html",
  "support.html",
  "status.html",
  "404.html",
  "robots.txt",
  "sitemap.xml"
];

await rm(dist, { recursive: true, force: true });
await mkdir(dist, { recursive: true });
await cp(publicDir, dist, { recursive: true });

for (const file of files) {
  const input = join(source, file);
  const output = join(dist, file);
  let content = await readFile(input, "utf8");
  content = content.replaceAll("__TORVE_DONATION_URL__", donationUrl);
  await mkdir(dirname(output), { recursive: true });
  await writeFile(output, content, "utf8");
}

console.log(`Built Torve website to ${outDir}`);
