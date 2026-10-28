"""Offline multi-turn, privacy, delivery and build acceptance tests."""
import asyncio
import json
import time
import sqlite3
from dataclasses import replace

import httpx
import pytest
from cryptography.fernet import Fernet

from discord_support.builds import Builds,MANIFEST
from discord_support.conversation import Conversations,Event,Facts,Plan,allowed_channel
from discord_support.gateway import make_gateway
from discord_support.service import Config,Store

GUILD="1507992418358919338"
APP="1507992418358919339"
HELP="1508057781297741884"
FORUM="1508057928005845102"
THREAD="1561508924111323297"
STAFF="1563813545911787580"
ROLE="1508002604536434809"
USER="1507992418358919340"
OTHER="1507992418358919341"


def cfg_for(tmp_path,**kwargs):
    return replace(Config(enabled=True,public_key="0"*64,application_id=APP,guild_id=GUILD,channels=(HELP,),forum_ids=(FORUM,),
                          staff_channel=STAFF,staff_roles=(ROLE,),bot_token="mock",data_key=Fernet.generate_key().decode(),
                          database=str(tmp_path/"conversation.sqlite3"),ai_enabled=True,openai_key="mock",openai_model="mock",
                          autonomous_enabled=True,notice_ready=True),**kwargs)


def plan(**kwargs):
    data={"intent":"clarify","category":"playback","explanation":"","summary":"Playback issue","article_ids":[],"question_ids":["platform","version"],"check_ids":[],
          "facts":Facts(platform="",version="",build="",distribution="",device="",expected="",actual="",reproduction="",tried="",outcome="")}
    data.update(kwargs)
    return Plan(**data)


class API:
    def __init__(self):
        self.sent=[];self.receipts=[];self.original={};self.private=True;self.archived=False;self.fail_send=False;self.bad_parent=False;self.tag_changes=[];self.thread_owner=USER;self.tag_ids=[]

    async def request(self,method,path,**kwargs):
        if method=="POST":
            if self.fail_send:
                raise httpx.ReadTimeout("ambiguous")
            (self.receipts if kwargs["json"]["content"].startswith("Received.") else self.sent).append(kwargs["json"])
            return {"id":"123"}
        if method=="PATCH":
            self.tag_changes.append(kwargs["json"]);self.tag_ids=kwargs["json"]["applied_tags"]
            return {"id":THREAD}
        if "/messages/" in path:
            return self.original[path.rsplit("/",1)[-1]]
        if path.endswith("/roles"):
            return [{"id":GUILD,"permissions":"0"},{"id":ROLE,"permissions":"8"}]
        channel=path.rsplit("/",1)[-1]
        data={"guild_id":GUILD,"type":0,"permission_overwrites":[]}
        if channel==STAFF:
            data["permission_overwrites"]=[{"id":GUILD,"type":0,"deny":"1024"}] if self.private else []
        elif channel==THREAD:
            data.update(type=11,parent_id=FORUM if not self.bad_parent else "wrong",owner_id=self.thread_owner,applied_tags=self.tag_ids,thread_metadata={"archived":self.archived})
        elif channel==FORUM:
            data["type"]=15
            from discord_support.tags import TAG_NAMES
            data["available_tags"]=[{"name":name,"id":str(i)} for i,name in enumerate(TAG_NAMES)]
        return data


@pytest.fixture
def setup(tmp_path):
    cfg=cfg_for(tmp_path);store=Store(cfg);api=API();events=[];calls=[]
    async def planner(state,text,build):
        calls.append((state,text,build))
        return plan()
    client=httpx.AsyncClient(transport=httpx.MockTransport(lambda r:httpx.Response(503)))
    engine=Conversations(cfg,store,api,client,events.append,planner=planner)
    yield engine,store,api,events,calls
    asyncio.run(client.aclose());store.db.close()


def ev(number=1,**kwargs):
    values=dict(id=str(1700000000000000000+number),guild=GUILD,channel=HELP,user=USER,text="Playback is black, audio works")
    values.update(kwargs);return Event(**values)


def consent(engine,user=USER):
    engine.preference_set(engine.store.identity(user),1)


def test_notice_then_consent_retrieves_only_authors_original_message(setup):
    engine,store,api,events,calls=setup
    original=ev()
    api.original[original.id]={"author":{"id":USER},"content":original.text}
    async def run():
        await engine.receive(original);await engine.deliver()
        assert calls==[]
        assert store.db.execute("SELECT count(*) FROM support_inbox").fetchone()[0]==0
        assert original.text not in api.sent[0]["content"]
        assert "Agree and start" in json.dumps(api.sent[0])
        assert "Only the author" in await engine.control("accept",original.id,OTHER,HELP)
        assert "agreed" in await engine.control("accept",original.id,USER,HELP)
        await engine.tick()
    asyncio.run(run())
    assert len(calls)==1
    assert store.db.execute("SELECT count(*) FROM support_cases").fetchone()[0]==1
    assert api.sent[-1]["allowed_mentions"]=={"parse":[]}
    assert api.sent[-1]["message_reference"]["message_id"]==original.id


def test_multi_turn_memory_no_repeated_diagnostics_and_member_isolation(setup):
    engine,store,api,events,calls=setup
    consent(engine);consent(engine,OTHER)
    async def run():
        await engine.receive(ev());await engine.tick()
        await engine.receive(ev(2,text="Windows 1.3.0. Restart did not help."));await engine.tick()
        await engine.receive(ev(3,user=OTHER,text="My phone list is missing"));await engine.tick()
    asyncio.run(run())
    assert "Playback is black" in json.dumps(calls[1][0])
    assert calls[1][0]["asked"]==["platform","version"]
    assert "Playback is black" not in json.dumps(calls[2][0])
    assert store.db.execute("SELECT count(*) FROM support_cases").fetchone()[0]==2
    # Repeated empty diagnostics stall and create a real private handoff instead.
    case=engine.latest(store.identity(USER),HELP)
    assert case["status"]=="queued"
    assert store.get_ticket(engine.unseal(case["data"])["ticket_id"])


@pytest.mark.parametrize("changed",[{"bot":True},{"webhook":True},{"guild":"wrong"},{"channel":STAFF},{"channel":"1507992418358999999"}])
def test_only_explicit_public_support_scope(setup,changed):
    engine,store,api,events,calls=setup;consent(engine)
    asyncio.run(engine.receive(ev(**changed)))
    assert not store.db.execute("SELECT 1 FROM support_inbox").fetchone()


def test_forum_parent_and_archive_are_checked_fresh(setup):
    engine,store,api,events,calls=setup
    assert asyncio.run(allowed_channel(engine.cfg,api,THREAD))
    api.archived=True
    assert not asyncio.run(allowed_channel(engine.cfg,api,THREAD))
    api.archived=False;api.bad_parent=True
    assert not asyncio.run(allowed_channel(engine.cfg,api,THREAD))


def test_inbox_replay_rate_limits_and_no_raw_identifiers_in_storage(setup):
    engine,store,api,events,calls=setup;consent(engine)
    async def run():
        await engine.receive(ev(text="password=hunter2 me@example.com https://private.invalid "+USER))
        await engine.receive(ev())
        assert store.db.execute("SELECT count(*) FROM support_inbox").fetchone()[0]==1
        for n in range(2,9):
            await engine.receive(ev(n))
    asyncio.run(run())
    assert store.db.execute("SELECT count(*) FROM support_inbox").fetchone()[0]==6
    blob=store.db.execute("SELECT data FROM support_inbox LIMIT 1").fetchone()[0]
    assert b"hunter2" not in blob and USER.encode() not in blob
    value=engine.unseal(blob)
    assert "hunter2" not in value["text"] and "me@example.com" not in value["text"] and USER not in value["text"]


def test_restart_recovers_inbox_and_reply_without_reprocessing_event(setup):
    engine,store,api,events,calls=setup;consent(engine)
    async def run():
        await engine.receive(ev())
        recovered=Conversations(engine.cfg,store,api,engine.client,events.append,planner=engine.planner)
        await recovered.tick()
        await recovered.receive(ev());await recovered.tick()
    asyncio.run(run())
    assert len(calls)==1 and len(api.sent)==1


def test_ambiguous_send_is_not_blindly_retried(setup):
    engine,store,api,events,calls=setup;consent(engine);api.fail_send=True
    async def run():
        await engine.receive(ev());await engine.tick()
        api.fail_send=False
        recovered=Conversations(engine.cfg,store,api,engine.client,events.append,planner=engine.planner)
        await recovered.tick()
    asyncio.run(run())
    assert not api.sent
    assert engine.metrics()["uncertain_replies"]==2  # Receipt and answer were both ambiguous.


def test_forget_cancels_pending_work_and_old_consent_cannot_revive_it(setup):
    engine,store,api,events,calls=setup;consent(engine)
    original=ev();api.original[original.id]={"author":{"id":USER},"content":original.text}
    earlier=time.time()-1
    async def run():
        await engine.receive(original)
        assert "deleted" in await engine.control("forget","",USER,HELP)
        await engine.tick()
        assert "superseded" in await engine.control("accept",original.id,USER,HELP,requested_at=earlier)
        await engine.receive(ev(2));await engine.tick()
    asyncio.run(run())
    assert not calls and not api.sent
    assert engine.preference(store.identity(USER))==0
    for table in ("support_cases","support_inbox","support_outbox","support_repairs"):
        assert store.db.execute(f"SELECT count(*) FROM {table}").fetchone()[0]==0


def test_human_takeover_updates_evidence_and_member_can_confirm_resolution(setup):
    engine,store,api,events,calls=setup;consent(engine)
    async def run():
        await engine.receive(ev());await engine.tick()
        case=engine.latest(store.identity(USER),HELP)
        assert "saved" in await engine.control("human",case["id"],USER,HELP)
        await engine.staff_update(case["id"],OTHER,status="investigating",reply="We are testing the report.")
        await engine.receive(ev(2,text="Same problem on a second file"));await engine.tick()
        assert engine.case(case["id"])["status"]=="investigating"
        assert len(calls)==1
        await engine.receive(ev(3,text="fixed"));await engine.tick()
        assert engine.case(case["id"])["status"]=="resolved"
        assert "No case found" in await engine.control("resolve",case["id"],OTHER,HELP)
    asyncio.run(run())


def test_unsupported_model_actions_and_failed_ai_escalate_safely(setup):
    engine,store,api,events,calls=setup;consent(engine)
    async def bad(*args):
        return plan(intent="answer",article_ids=["install"],explanation="I deployed a fix. Run curl bad.example",question_ids=[])
    engine.planner=bad
    async def run():
        await engine.receive(ev());await engine.tick()
    asyncio.run(run())
    assert "I deployed" not in api.sent[0]["content"]
    assert engine.latest(store.identity(USER),HELP)["status"]=="queued"
    assert "conversation_ai_failed" in events


def test_unverified_queue_does_not_claim_handoff_or_create_ticket(setup):
    engine,store,api,events,calls=setup;consent(engine);api.private=False
    async def human(*args):return plan(intent="human")
    engine.planner=human
    asyncio.run(engine.receive(ev()));asyncio.run(engine.tick())
    assert "no staff report was filed" in api.sent[0]["content"]
    assert not store.db.execute("SELECT 1 FROM tickets").fetchone()


def test_ai_payload_redacted_bounded_and_no_tools_or_route_ids(setup):
    engine,store,api,events,calls=setup
    sent=[]
    def respond(request):
        sent.append(json.loads(request.content))
        return httpx.Response(200,json={"status":"completed","output":[{"content":[{"type":"output_text","text":plan().model_dump_json()}]}]})
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            engine.client=client
            await engine.plan({"history":[{"role":"user","text":"Playback fails"}]},"what next",{})
    asyncio.run(run())
    assert sent[0]["store"] is False and "tools" not in sent[0]
    assert sent[0]["text"]["format"]["strict"] is True
    assert USER not in json.dumps(sent) and HELP not in json.dumps(sent)


def test_gateway_requests_only_required_intents_and_bounded_cache(setup):
    engine,store,api,events,calls=setup
    gateway=make_gateway(engine.cfg,engine,events.append)
    assert gateway.intents.message_content and gateway.intents.guild_messages and gateway.intents.guilds
    assert not gateway.intents.members and not gateway.intents.presences and not gateway.intents.dm_messages


def test_expiry_removes_case_memory_and_pending_reply(setup):
    engine,store,api,events,calls=setup;consent(engine)
    async def run():await engine.receive(ev());await engine.tick()
    asyncio.run(run())
    store.db.execute("UPDATE support_cases SET expires=?",(time.time()-1,));store.db.commit()
    engine.purge()
    assert not store.db.execute("SELECT 1 FROM support_cases").fetchone()
    assert not store.db.execute("SELECT 1 FROM support_outbox WHERE case_id IS NOT NULL").fetchone()
    # The content-free delivery receipt has its independent maximum 24h lifetime.


def manifest():
    return {"source":{"base_commit":"a"*40,"includes_uncommitted_worktree_changes":False},"channels":{"stable":{"windows":{"version":"1.3.0","status":"available","url":"https://torve.app/downloads/windows/test.msi"}}}}


def test_current_build_uses_fixed_manifest_cache_and_distinguishes_versions():
    requests=[]
    def handle(request):
        requests.append(str(request.url));return httpx.Response(200,json=manifest())
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            builds=Builds(client)
            assert "older" in await builds.describe("windows","1.2.0")
            assert "matches" in await builds.describe("windows","1.3.0")
            assert "another distribution" in await builds.describe("windows","1.4.0")
            assert "not listed" in await builds.describe("ios")
            data=await builds.current("windows")
            assert data["source_commit"]=="a"*40 and len(data["manifest_sha256"])==64
    asyncio.run(run())
    assert requests==[MANIFEST]


def test_failed_build_refresh_never_presents_stale_cache_as_current():
    answers=[httpx.Response(200,json=manifest()),httpx.Response(503)]
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r:answers.pop(0))) as client:
            builds=Builds(client);assert (await builds.current())["verified"]
            builds.fetched=0
            assert not (await builds.current())["verified"]
    asyncio.run(run())


def test_autonomous_mode_cannot_start_without_notice_ai_or_separate_destinations(tmp_path):
    for change in ({"notice_ready":False},{"ai_enabled":False},{"staff_channel":HELP}):
        with pytest.raises(ValueError):cfg_for(tmp_path,**change).validate()


def test_forget_http_ack_does_not_wait_for_active_diagnosis_lock(tmp_path):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from fastapi.testclient import TestClient
    from discord_support.service import create_app
    from test_service import payload,signed
    private=Ed25519PrivateKey.generate()
    public=private.public_key().public_bytes(serialization.Encoding.Raw,serialization.PublicFormat.Raw).hex()
    cfg=cfg_for(tmp_path,public_key=public)
    delivered=[]
    def router(request):
        if request.url.path.endswith("/roles"):
            return httpx.Response(200,json=[{"id":GUILD,"permissions":"0"},{"id":ROLE,"permissions":"8"}])
        if request.method=="PATCH":
            delivered.append(json.loads(request.content));return httpx.Response(200,json={"id":"reply"})
        return httpx.Response(200,json={"guild_id":GUILD,"type":0,"permission_overwrites":[{"id":GUILD,"type":0,"deny":"1024"}] if STAFF in request.url.path else []})
    app=create_app(cfg,transport=httpx.MockTransport(router))
    with TestClient(app) as client:
        engine=app.state.conversations;owner=app.state.store.identity(USER)
        consent(engine);app.state.store.ticket(owner,"legacy private request")
        lock=engine.lock(owner);client.portal.call(lock.acquire)
        body=payload("forget");body["channel_id"]=HELP;body["member"]["user"]["id"]=USER
        started=time.monotonic()
        response=signed(client,private,body)
        assert response.json()=={"type":5,"data":{"flags":64}}
        assert time.monotonic()-started<3
        client.portal.call(lock.release)
        for _ in range(20):
            client.get("/health")
            if delivered:break
        assert delivered and "deleted" in delivered[-1]["content"]
        assert not app.state.store.db.execute("SELECT 1 FROM tickets WHERE owner=?",(owner,)).fetchone()
        assert client.get("/ready").status_code==503  # Mock transport never starts a real gateway.


def test_token_budget_refuses_network_call(setup):
    engine,store,api,events,calls=setup
    engine.cfg=replace(engine.cfg,daily_token_budget=1)
    async def run():
        with pytest.raises(RuntimeError,match="budget"):
            await engine.plan({},"playback fails",{})
    asyncio.run(run())


def test_repair_completion_reports_proposal_not_deployment_and_optout_cancels_work(setup):
    engine,store,api,events,calls=setup;consent(engine)
    async def run():
        await engine.receive(ev());await engine.tick()
        case=engine.latest(store.identity(USER),HELP)
        engine.repairs.submit(case["id"],case["owner"],{"kind":"feature"})
        engine.repairs.update(case["id"],"proposal_ready",{"private_path":"never public"})
        await engine.tick()
        assert "not been merged or deployed" in api.sent[-1]["content"]
        assert "private_path" not in json.dumps(api.sent)
        store.db.execute("UPDATE support_repairs SET state='assessing'");store.db.commit()
        await engine.control("stop","",USER,HELP)
        assert store.db.execute("SELECT state FROM support_repairs").fetchone()[0]=="human_review"
    asyncio.run(run())


def test_receipt_is_delivered_before_planner_and_during_an_earlier_turn(setup):
    engine,store,api,events,calls=setup;consent(engine)
    async def run():
        entered=asyncio.Event();release=asyncio.Event()
        async def slow(state,text,build):
            assert api.receipts  # Acknowledgement already sent before any model work.
            entered.set();await release.wait();return plan()
        engine.planner=slow
        await engine.receive(ev())
        work=asyncio.create_task(engine.tick())
        await asyncio.wait_for(entered.wait(),2)
        await engine.receive(ev(2,text="Additional symptom"))
        await engine.deliver(receipts_only=True)
        assert len(api.receipts)==2
        release.set();await work
    asyncio.run(run())


def test_real_forum_tags_follow_question_category_status_and_confirmed_solution(setup):
    from discord_support.tags import TAG_NAMES
    engine,store,api,events,calls=setup;consent(engine)
    async def run():
        await engine.receive(ev(channel=THREAD));await engine.tick()
        names={TAG_NAMES[int(x)] for x in api.tag_ids}
        assert {"Question","Playback","Needs Info"}<=names
        assert len(api.tag_ids)<=5
        case=engine.latest(store.identity(USER),THREAD)
        await engine.control("resolve",case["id"],USER,THREAD);await engine.tick()
        names={TAG_NAMES[int(x)] for x in api.tag_ids}
        assert {"Resolved","Solution"}<=names and "Needs Info" not in names
    asyncio.run(run())


def test_participant_does_not_relabel_another_members_forum_post(setup):
    engine,store,api,events,calls=setup;consent(engine,OTHER)
    asyncio.run(engine.receive(ev(channel=THREAD,user=OTHER)));asyncio.run(engine.tick())
    assert not api.tag_changes


def test_tag_write_failure_is_observable_and_queued_for_bounded_retry(setup):
    engine,store,api,events,calls=setup;consent(engine)
    original=api.request
    async def fail(method,path,**kwargs):
        if method=="PATCH":raise RuntimeError("forbidden")
        return await original(method,path,**kwargs)
    api.request=fail
    asyncio.run(engine.receive(ev(channel=THREAD)));asyncio.run(engine.tick())
    assert "forum_tags_failed" in events
    assert engine.metrics()["pending_tags"]==1


def test_tag_taxonomy_fits_discord_twenty_tag_limit():
    from discord_support.tags import TAG_NAMES
    assert len(TAG_NAMES)==20 and len(set(TAG_NAMES))==20


def test_recovery_is_explicit_and_does_not_run_when_definitions_are_imported(setup):
    engine,store,api,events,calls=setup;consent(engine)
    asyncio.run(engine.receive(ev()))
    store.db.execute("UPDATE support_outbox SET state='sending'");store.db.commit()
    recovered=Conversations(engine.cfg,store,api,engine.client,events.append,planner=engine.planner)
    assert store.db.execute("SELECT state FROM support_outbox").fetchone()[0]=="sending"
    recovered.recover_inflight()
    assert store.db.execute("SELECT state FROM support_outbox").fetchone()[0]=="uncertain"


def test_slow_model_does_not_hold_sqlite_write_lock_needed_by_repair_worker(setup):
    engine,store,api,events,calls=setup;consent(engine)
    async def slow(*args):
        other=sqlite3.connect(engine.cfg.database,timeout=0.05)
        try:
            other.execute("INSERT INTO limits VALUES(?,?,?)",("independent-repair-worker",1,time.time()+60));other.commit()
        finally:
            other.close()
        return plan()
    engine.planner=slow
    asyncio.run(engine.receive(ev()));asyncio.run(engine.tick())
    assert store.db.execute("SELECT count FROM limits WHERE key='independent-repair-worker'").fetchone()[0]==1
