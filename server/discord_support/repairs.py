"""Encrypted Codex handoff queue. No public-bot process executes code or Git."""
import json
import re
import time

DISALLOWED = re.compile(r"\b(?:piracy|pirated|bypass|crack|drm|stolen|scrape|entitlement|premium|"
                        r"payment|billing|authentication|authorisation|authorization|credentials|"
                        r"password|api.?key|security vulnerability|copyright)\b",re.I)


def eligible(report):
    facts = report.get("facts",{})
    text = json.dumps({"summary":report.get("summary",""),"facts":facts})
    if report.get("kind")!="bug" or DISALLOWED.search(text):
        return False,"This request needs human policy or scope review."
    if not all(facts.get(k,"").strip() for k in ("platform","version","actual","expected","reproduction")):
        return False,"A reproducible report with platform, version and expected/actual behaviour is required."
    build = report.get("published_build",{})
    if not build.get("verified") or time.time()-build.get("checked_at",0)>600:
        return False,"Current published build metadata is unavailable or stale."
    if build.get("source_has_uncommitted_changes",True) or not re.fullmatch(r"[0-9a-f]{40}",build.get("source_commit","")):
        return False,"The published build cannot be mapped to a complete clean source revision; human provenance review is required."
    from .builds import ALIASES
    platform = ALIASES.get(facts["platform"].lower(),facts["platform"].lower())
    entry = build.get("platforms",{}).get(platform)
    if not entry or not entry.get("available") or facts["version"]!=entry["version"]:
        return False,"Reproduce on the verified current stable version before an automatic repair handoff."
    if facts.get("distribution")!="stable" or entry.get("build") and facts.get("build")!=entry["build"]:
        return False,"The exact public build and distribution must match; a matching version alone is insufficient."
    return True,"Ready for read-only Codex assessment; no fix has been validated yet."


class RepairQueue:
    def __init__(self,store):
        self.store = store
        store.db.execute("""CREATE TABLE IF NOT EXISTS support_repairs(
          case_id TEXT PRIMARY KEY, owner TEXT, report BLOB, state TEXT, result BLOB,
          created REAL, updated REAL, expires REAL)""")
        if "expires" not in {r[1] for r in store.db.execute("PRAGMA table_info(support_repairs)")}:
            store.db.execute("ALTER TABLE support_repairs ADD COLUMN expires REAL")
            store.db.execute("UPDATE support_repairs SET expires=created+?",(30*86400,))
        store.db.commit()

    def submit(self,case_id,owner,report):
        ok,reason = eligible(report)
        state = "assessment_queued" if ok else "human_review"
        self.store.db.execute("INSERT OR IGNORE INTO support_repairs VALUES(?,?,?,?,?,?,?,?)",
                              (case_id,owner,self.store.cipher.encrypt(json.dumps(report).encode()),state,
                               self.store.cipher.encrypt(json.dumps({"reason":reason}).encode()),time.time(),time.time(),min(report.get("expires_at",time.time()+30*86400),time.time()+30*86400)))
        self.store.db.commit()
        return state,reason

    def update(self,case_id,state,result):
        if state not in ("assessing","human_review","proposal_ready","failed"):
            raise ValueError("invalid_repair_state")
        self.store.db.execute("UPDATE support_repairs SET state=?,result=?,updated=? WHERE case_id=?",
                              (state,self.store.cipher.encrypt(json.dumps(result).encode()),time.time(),case_id))
        self.store.db.commit()

    def forget(self,owner):
        self.store.db.execute("DELETE FROM support_repairs WHERE owner=?",(owner,))
        self.store.db.commit()

    def purge(self):
        self.store.db.execute("DELETE FROM support_repairs WHERE expires<?",(time.time(),))
        self.store.db.commit()
