import { readdir, readFile, stat } from "node:fs/promises";
import { extname, join, relative } from "node:path";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

const packageRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const rootFlagIndex = process.argv.indexOf("--root");
const siteRoot = rootFlagIndex >= 0 ? join(packageRoot, process.argv[rootFlagIndex + 1]) : join(packageRoot, "src");
const expectDonation = process.argv.includes("--expect-donation");
const validatingSource = siteRoot.endsWith("src");

const banned = [
  /\bpremium\b/i,
  /\bupgrade\b/i,
  /\bunlock\b/i,
  /\bcheckout\b/i,
  /\bstripe\b/i,
  /\bpaddle\b/i,
  /\blifetime access\b/i,
  /\bmonthly plan\b/i,
  /\bsubscription plan\b/i,
  /\bprice id\b/i,
  /\bbilling portal\b/i
];

async function walk(dir) {
  const entries = await readdir(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...await walk(full));
    } else {
      files.push(full);
    }
  }
  return files;
}

function fail(message) {
  console.error(message);
  process.exitCode = 1;
}

const files = (await walk(siteRoot)).filter((file) => [".html", ".css", ".js", ".xml", ".txt"].includes(extname(file)));
const htmlFiles = files.filter((file) => extname(file) === ".html");
const routes = new Set(htmlFiles.map((file) => "/" + relative(siteRoot, file).replaceAll("\\", "/")));

for (const file of files) {
  const text = (await readFile(file, "utf8")).replaceAll("Donations are optional and never unlock features.", "");
  const rel = relative(siteRoot, file);
  for (const pattern of banned) {
    if (pattern.test(text)) {
      fail(`Banned paid-product wording matched ${pattern} in ${rel}`);
    }
  }
  if (!validatingSource && /__TORVE_DONATION_URL__/.test(text)) {
    fail(`Unresolved donation placeholder in ${rel}`);
  }
  if (/sk_live_|pk_live_|whsec_|-----BEGIN .*PRIVATE KEY-----/.test(text)) {
    fail(`Potential secret pattern in ${rel}`);
  }
}

for (const file of htmlFiles) {
  const text = await readFile(file, "utf8");
  const rel = relative(siteRoot, file);
  for (const required of ["<title>", "meta name=\"description\"", "canonical", "og:title", "twitter:card"]) {
    if (!text.includes(required)) {
      fail(`Missing ${required} in ${rel}`);
    }
  }
  if (!/<main id="main"/.test(text)) {
    fail(`Missing main landmark in ${rel}`);
  }
  if (!/Skip to content/.test(text)) {
    fail(`Missing skip link in ${rel}`);
  }
  if (/<img\b(?![^>]*\balt=)/i.test(text)) {
    fail(`Image without alt text in ${rel}`);
  }
}

const requiredRoutes = [
  "/index.html",
  "/download.html",
  "/source.html",
  "/signin.html",
  "/signup.html",
  "/account.html",
  "/privacy.html",
  "/terms.html",
  "/account-deletion.html",
  "/support.html",
  "/status.html"
];
for (const route of requiredRoutes) {
  if (!routes.has(route)) {
    fail(`Missing route ${route}`);
  }
}

const linkPattern = /href="([^"]+)"/g;
for (const file of htmlFiles) {
  const text = await readFile(file, "utf8");
  const rel = relative(siteRoot, file);
  for (const match of text.matchAll(linkPattern)) {
    const href = match[1];
    if (href.startsWith("#") || href.startsWith("http") || href.startsWith("mailto:") || href.startsWith("tel:")) continue;
    const target = href.split("#")[0].split("?")[0];
    if (!target || target === "/" || target.startsWith("/downloads/")) continue;
    const normalized = target.startsWith("/") ? target.slice(1) : target;
    const candidate = join(siteRoot, normalized);
    try {
      const info = await stat(candidate);
      if (!info.isFile()) fail(`Link target is not a file from ${rel}: ${href}`);
    } catch {
      fail(`Broken local link from ${rel}: ${href}`);
    }
  }
}

const index = await readFile(join(siteRoot, "index.html"), "utf8");
if (!index.includes("Your media companion, free and open source.")) {
  fail("Home headline is missing the required copy.");
}
if (!index.includes("Torve is free software. There are no subscriptions, no paid tiers, and no purchase required.")) {
  fail("Home supporting copy is missing the required copy.");
}
if (!index.includes("An account is required for cross-device sync, device linking, and account-backed data. Local functionality does not require a paid plan.")) {
  fail("Home account copy is missing the required copy.");
}

if (expectDonation) {
  let donationRendered = false;
  for (const file of files) {
    const text = await readFile(file, "utf8");
    donationRendered ||= text.includes("https://example.invalid/donate");
  }
  if (!donationRendered) {
    fail("Donation test URL was not rendered.");
  }
} else {
  for (const file of files) {
    const text = await readFile(file, "utf8");
    if (/https:\/\/example\.invalid\/donate/.test(text)) {
      fail(`Donation test URL leaked into ${relative(siteRoot, file)}`);
    }
  }
}

console.log(`Validated ${files.length} website files.`);
