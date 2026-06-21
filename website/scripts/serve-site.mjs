import { createServer } from "node:http";
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { extname, join, normalize, resolve, sep } from "node:path";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

const packageRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const rootFlagIndex = process.argv.indexOf("--root");
const portFlagIndex = process.argv.indexOf("--port");
const root = resolve(packageRoot, rootFlagIndex >= 0 ? process.argv[rootFlagIndex + 1] : "dist");
const port = Number(portFlagIndex >= 0 ? process.argv[portFlagIndex + 1] : 4173);

const types = new Map([
  [".css", "text/css; charset=utf-8"],
  [".html", "text/html; charset=utf-8"],
  [".js", "text/javascript; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".png", "image/png"],
  [".txt", "text/plain; charset=utf-8"],
  [".xml", "application/xml; charset=utf-8"]
]);

function resolveRequest(url) {
  const pathname = decodeURIComponent(new URL(url, "http://localhost").pathname);
  const target = pathname === "/" ? "index.html" : pathname.slice(1);
  const full = normalize(join(root, target));
  if (full !== root && !full.startsWith(root + sep)) {
    return null;
  }
  return full;
}

const server = createServer(async (request, response) => {
  const full = resolveRequest(request.url ?? "/");
  if (!full) {
    response.writeHead(403);
    response.end("Forbidden");
    return;
  }

  try {
    const info = await stat(full);
    if (!info.isFile()) throw new Error("Not a file");
    response.writeHead(200, {
      "Content-Type": types.get(extname(full)) ?? "application/octet-stream"
    });
    createReadStream(full).pipe(response);
  } catch {
    response.writeHead(404, { "Content-Type": "text/html; charset=utf-8" });
    createReadStream(join(root, "404.html")).pipe(response);
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`Torve website preview: http://127.0.0.1:${port}/`);
});
