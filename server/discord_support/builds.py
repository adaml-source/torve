"""Read only the operator-owned public release manifest; never follow user URLs."""
import re
import time
import hashlib
from urllib.parse import urlsplit

MANIFEST = "https://torve.app/downloads/releases.json"
ALIASES = {"fire tv":"fire_tv","firetv":"fire_tv","google tv":"android_tv","android tv":"android_tv",
           "android":"android_mobile","android phone":"android_mobile","iphone":"ios","ipad":"ios","apple tv":"tvos"}


def version_tuple(value):
    match = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)(?:[-+][0-9A-Za-z.-]+)?",value.strip())
    return tuple(map(int,match.groups())) if match else None


class Builds:
    def __init__(self,client):
        self.client = client
        self.cached = None
        self.fetched = 0

    async def current(self,platform=""):
        try:
            if not self.cached or time.time()-self.fetched > 300:
                async with self.client.stream("GET",MANIFEST,follow_redirects=False,timeout=10) as r:
                    r.raise_for_status()
                    raw = bytearray()
                    async for chunk in r.aiter_bytes():
                        raw.extend(chunk)
                        if len(raw)>262144:
                            raise ValueError("manifest_too_large")
                import json
                data = json.loads(raw)
                if not isinstance(data.get("channels",{}).get("stable"),dict):
                    raise ValueError("invalid_manifest")
                self.cached,self.fetched = data,time.time()
                self.digest = hashlib.sha256(raw).hexdigest()
            stable = self.cached["channels"]["stable"]
            selected = ALIASES.get(platform.lower().strip(),platform.lower().strip())
            result = {}
            for key,entry in stable.items():
                if selected and key!=selected:
                    continue
                if not isinstance(entry,dict):
                    continue
                version = str(entry.get("version",""))
                url = str(entry.get("url",""))
                # Manifest metadata is untrusted input, not model instructions.
                if not re.fullmatch(r"[0-9A-Za-z.+_-]{1,60}",version):
                    continue
                if url and (urlsplit(url).scheme!="https" or urlsplit(url).hostname not in ("torve.app","play.google.com","apps.apple.com")):
                    continue
                build = str(entry.get("version_code",entry.get("build",entry.get("build_number",""))))
                result[key] = {"version":version,"build":build[:30] if re.fullmatch(r"[0-9A-Za-z._-]*",build) else "",
                               "available":entry.get("status")=="available"}
            provenance = self.cached.get("source",{})
            commit = str(provenance.get("base_commit",""))
            return {"verified":True,"source":MANIFEST,"checked_at":int(self.fetched),"channel":"stable","platforms":result,
                    "manifest_sha256":self.digest,
                    "source_commit":commit if re.fullmatch(r"[0-9a-f]{40}",commit) else "",
                    "source_has_uncommitted_changes":provenance.get("includes_uncommitted_worktree_changes") is not False}
        except Exception:
            # No stale cache is presented as current after a failed refresh.
            return {"verified":False,"source":MANIFEST,"reason":"Current published build could not be verified."}

    async def describe(self,platform,installed=""):
        data = await self.current(platform)
        if not data["verified"]:
            return data["reason"]+" Check https://torve.app/download.html."
        lines = []
        for name,item in data["platforms"].items():
            suffix = " (available)" if item["available"] else " (not marked available)"
            lines.append(f"Published stable {name}: {item['version']}"+(f" · build {item['build']}" if item['build'] else "")+suffix)
            have,new = version_tuple(installed),version_tuple(item["version"])
            if have and new:
                lines.append("Your version is older than this published version." if have<new else
                             "Your semantic version matches; compare the full build/distribution too." if have==new else
                             "Your version is newer than this manifest entry; it may be another distribution or beta.")
        if not lines:
            lines.append("This platform is not listed with a verifiable version in the public stable manifest.")
        lines.append("Source: "+MANIFEST)
        lines.append("This checks published metadata, not your device, store rollout, private beta or whether a bug is fixed.")
        return "\n".join(lines)
