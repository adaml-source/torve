"""Durable forum tagging. No channel creation/deletion or taxonomy mutation."""
import json
import time

PLATFORMS={"android_tv":"Android TV","fire_tv":"Fire TV","windows":"Windows","android_mobile":"Android Mobile","ios":"iOS / tvOS","tvos":"iOS / tvOS"}
CATEGORIES={"setup":"Setup","playback":"Playback","iptv_epg":"IPTV / EPG","sources":"Sources","addons":"Addons","libraries":"Libraries","account":"Account","feature":"Feature","question":"Question"}
STATUSES={"triage":"New","waiting_member":"Needs Info","needs_info":"Needs Info","queued":"New","needs_human":"New","paused":"New","human":"New","investigating":"Investigating","resolved":"Resolved","duplicate":"Duplicate"}
STATUS_NAMES={"New","Needs Info","Investigating","Resolved","Duplicate"}
TAG_NAMES=list(dict.fromkeys([*PLATFORMS.values(),*CATEGORIES.values(),*sorted(STATUS_NAMES),"Solution"]))


def labels(case,state):
    result=[]
    platform=PLATFORMS.get(state.get("facts",{}).get("platform",""))
    if platform:result.append(platform)
    category=CATEGORIES.get(state.get("category",""))
    if category:result.append(category)
    if state.get("kind") in ("answer","clarify","check") and "Question" not in result:
        result.append("Question")
    result.append(STATUSES.get(case["status"],"New"))
    if case["status"]=="resolved":result.append("Solution")
    return result[:5]


class Tags:
    def __init__(self,engine):
        self.engine=engine
        engine.store.db.execute("""CREATE TABLE IF NOT EXISTS support_tag_jobs(
          id TEXT PRIMARY KEY, owner TEXT, case_id TEXT, data BLOB, attempts INTEGER,
          due REAL, created REAL)""")
        engine.store.db.commit()

    def queue(self,case,state):
        e=self.engine
        channel=state["route"]["channel"]
        if not e.cfg.tagging_enabled or channel in e.cfg.channels:
            return
        latest=e.latest(case["owner"],channel)
        if latest and latest["id"]!=case["id"]:
            return
        data={"route":state["route"],"names":labels(case,state),"version":case["version"]}
        e.store.db.execute("INSERT OR REPLACE INTO support_tag_jobs VALUES(?,?,?,?,0,?,?)",
                           (e.store.identity(channel),case["owner"],case["id"],e.seal(data),time.time(),time.time()))

    async def flush(self):
        e=self.engine
        rows=e.store.db.execute("SELECT id,owner,case_id,data,attempts FROM support_tag_jobs WHERE attempts<5 AND due<=? ORDER BY created LIMIT 4",(time.time(),)).fetchall()
        for key,owner,case_id,encrypted,attempts in rows:
            async with e.lock(owner):
                case=e.case(case_id)
                data=e.unseal(encrypted)
                try:
                    if not case or case["version"]!=data["version"]:
                        e.store.db.execute("DELETE FROM support_tag_jobs WHERE id=?",(key,));e.store.db.commit();continue
                    route=data["route"]
                    thread=await e.api.request("GET",f"/channels/{route['channel']}")
                    if thread.get("guild_id")!=e.cfg.guild_id or thread.get("type")!=11 or thread.get("parent_id") not in e.cfg.forum_ids:
                        raise ValueError("tag_destination_changed")
                    if thread.get("owner_id")!=route.get("user"):
                        # A participant's separate case must not relabel the OP's post.
                        e.store.db.execute("DELETE FROM support_tag_jobs WHERE id=?",(key,));e.store.db.commit();continue
                    if thread.get("thread_metadata",{}).get("archived") or thread.get("thread_metadata",{}).get("locked"):
                        raise ValueError("thread_no_longer_active")
                    forum=await e.api.request("GET",f"/channels/{thread['parent_id']}")
                    if forum.get("guild_id")!=e.cfg.guild_id or forum.get("type")!=15:
                        raise ValueError("tag_parent_changed")
                    available={t["name"]:t["id"] for t in forum.get("available_tags",[])}
                    desired=data["names"]
                    if any(name not in available for name in desired):
                        raise ValueError("required_tag_missing")
                    # Keep an existing platform/topic choice when those facts are not
                    # known yet. Status and solution tags always follow actual state.
                    current=thread.get("applied_tags",[])
                    old_names=[name for name,id in available.items() if id in current]
                    if not set(desired)&set(PLATFORMS.values()):
                        desired=[*([n for n in old_names if n in PLATFORMS.values()][:1]),*desired]
                    topic_names=set(CATEGORIES.values())-{"Question","Feature"}
                    if not set(desired)&topic_names:
                        desired=[*([n for n in old_names if n in topic_names][:1]),*desired]
                    # The mandatory state labels have priority over optional extras.
                    state_names=[n for n in desired if n in STATUS_NAMES or n=="Solution"]
                    other=[n for n in dict.fromkeys(desired) if n not in state_names]
                    ids=[available[n] for n in [*other[:5-len(state_names)],*state_names]]
                    if set(current)!=set(ids):
                        await e.api.request("PATCH",f"/channels/{route['channel']}",json={"applied_tags":ids})
                    e.store.db.execute("DELETE FROM support_tag_jobs WHERE id=?",(key,))
                    e.record("forum_tags_updated")
                except Exception:
                    e.store.db.execute("UPDATE support_tag_jobs SET attempts=attempts+1,due=? WHERE id=?",(time.time()+min(60,5*2**attempts),key))
                    e.record("forum_tags_failed")
                e.store.db.commit()
