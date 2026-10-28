"""Focused website source-editor contracts; no live database or providers."""
import copy
import os
from types import SimpleNamespace

os.environ["DATABASE_URL"] = "sqlite:///source-setup-tests-unused.db"
os.environ["JWT_SECRET"] = "isolated-source-setup-test-key"

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from app.routers import source_setup as source


@pytest.fixture
def portal(monkeypatch):
    config = {"enableUsenet": True, "usenetProvider": "easynews", "usenetUsername": "qa-user",
        "usenetPassword": "[redacted]", "downloadClient": "torbox", "downloadClientApiKey": "[redacted]",
        "nzbIndexers": [{"type": "scenenzbs", "url": "", "apiKey": "[redacted]"},
            {"type": "nzbgeek", "url": "", "apiKey": "[redacted]"}],
        "qualityProfile": "balanced", "debridApiKey": "[redacted]", "enabledProviders": ["existing-provider"]}
    record = {"config_id": "qa-config", "updated_at": "v1", "config": config}
    settings = SimpleNamespace(settings={"language": "en"}, version=1, updated_at=None, updated_by_device_id=None)
    db = SimpleNamespace(commit=lambda: None)
    calls = []
    def context(request, response, uid, db, write=False):
        response.headers["Cache-Control"] = "private, no-store"
        return request.headers.get("X-Torve-Profile-Id", "default"), settings
    async def read(db, uid, profile):
        return (copy.deepcopy(record), {}, SimpleNamespace(config={})) if profile == "default" else (None, {}, None)
    async def upstream(method, path, uid, config_id=None, body=None, bearer=None):
        calls.append((method, path, copy.deepcopy(body)))
        if path.endswith("/secrets"):
            return {"nzb_indexers": [{"type": "scenenzbs", "url": "", "api_key": "scene-secret"},
                {"type": "nzbgeek", "url": "", "api_key": "geek-secret"}]}
        if method == "PATCH":
            record["config"].update(body); record["updated_at"] = "v2"
        return copy.deepcopy(record)
    monkeypatch.setattr(source, "context", context)
    monkeypatch.setattr(source, "read_source", read)
    monkeypatch.setattr(source, "upstream", upstream)
    app = FastAPI(); app.include_router(source.router)
    app.dependency_overrides[source.get_current_user_id] = lambda: "owner"
    app.dependency_overrides[source.require_premium] = lambda: "owner"
    app.dependency_overrides[source.get_db] = lambda: db
    return TestClient(app), record, settings, calls


def test_read_masks_secrets_and_disables_caching(portal):
    client, _, _, _ = portal
    result = client.get('/me/source-setup/usenet')
    assert result.status_code == 200
    assert result.headers['cache-control'] == 'private, no-store'
    body = result.json()
    assert body['values']['usenetPasswordSaved']
    assert 'usenetPassword' not in body['values']
    assert 'apiKey' not in body['values']['nzbIndexers'][0]
    assert 'debridApiKey' not in body['values']
    assert 'config_id' not in body


def test_usenet_save_is_partial_and_preserves_blank_secrets(portal):
    client, record, _, calls = portal
    original = copy.deepcopy(record['config'])
    revision = client.get('/me/source-setup/usenet').json()['revision']
    result = client.patch('/me/source-setup/usenet', json={'expectedRevision': revision,
        'values': {'enableUsenet': False, 'usenetPassword': '', 'downloadClientApiKey': ''}})
    assert result.status_code == 200
    assert calls[-1] == ('PATCH', '/configs/me', {'enableUsenet': False})
    for key in ('usenetPassword', 'downloadClientApiKey', 'nzbIndexers', 'debridApiKey', 'enabledProviders', 'qualityProfile'):
        assert record['config'][key] == original[key]


def test_indexer_removal_keeps_key_matched_by_identity_not_position(portal):
    client, record, _, calls = portal
    revision = client.get('/me/source-setup/usenet').json()['revision']
    result = client.patch('/me/source-setup/indexers', json={'expectedRevision': revision,
        'values': {'nzbIndexers': [{'type': 'nzbgeek', 'url': '', 'apiKey': ''}]}})
    assert result.status_code == 200
    assert calls[-1][2]['nzbIndexers'][0]['apiKey'] == 'geek-secret'
    assert record['config']['usenetUsername'] == 'qa-user'
    assert calls[-1][2]['nzbIndexerApiKey'] == 'geek-secret'
    assert 'geek-secret' not in result.text


def test_changed_indexer_requires_new_key(portal):
    client, _, _, calls = portal
    revision = client.get('/me/source-setup/usenet').json()['revision']
    result = client.patch('/me/source-setup/indexers', json={'expectedRevision': revision,
        'values': {'nzbIndexers': [{'type': 'custom', 'url': 'https://indexer.example.com', 'apiKey': ''}]}})
    assert result.status_code == 422
    assert not any(c[0] == 'PATCH' for c in calls)


def test_stale_form_cannot_overwrite_other_device(portal):
    client, _, _, calls = portal
    result = client.patch('/me/source-setup/usenet', json={'expectedRevision': 'old', 'values': {'enableUsenet': False}})
    assert result.status_code == 409
    assert not calls


def test_profile_read_does_not_fall_back_to_default(portal):
    client, _, _, _ = portal
    result = client.get('/me/source-setup/usenet', headers={'X-Torve-Profile-Id': 'child'})
    assert result.status_code == 200 and result.json()['configured'] is False


def test_failed_read_cannot_create_replacement(portal, monkeypatch):
    client, _, _, calls = portal
    async def failed(*args): raise HTTPException(502, 'Could not load')
    monkeypatch.setattr(source, 'read_source', failed)
    result = client.patch('/me/source-setup/usenet', json={'expectedRevision': source.revision(None), 'values': {'enableUsenet': False}})
    assert result.status_code == 502 and not calls


def test_replays_keep_ids_and_only_update_selected_profile(portal):
    client, _, settings, _ = portal
    settings.settings['web_replays_v1_settings'] = '{"enabled":true,"websites":[]}'
    headers = {'X-Torve-Profile-Id': 'child'}
    revision = client.get('/me/source-setup/replays', headers=headers).json()['revision']
    result = client.put('/me/source-setup/replays', headers=headers, json={'expectedRevision': revision,
        'values': {'enabled': True, 'websites': [{'id':'keep-history-id','name':'Replay library','url':'https://replays.example.com','enabled':False}]}})
    assert result.status_code == 200
    assert result.json()['values']['websites'][0]['id'] == 'keep-history-id'
    assert settings.settings['web_replays_v1_settings'] == '{"enabled":true,"websites":[]}'
    assert settings.settings['language'] == 'en'
    assert 'household_v1:child:navigation:web_replays_v1_settings' in settings.settings


def test_replays_conflict_and_invalid_stored_json_are_not_replaced(portal):
    client, _, settings, _ = portal
    result = client.put('/me/source-setup/replays', json={'expectedRevision':'stale','values':{'enabled':True,'websites':[]}})
    assert result.status_code == 409
    settings.settings['web_replays_v1_settings'] = '{broken'
    assert client.get('/me/source-setup/replays').status_code == 409


@pytest.mark.parametrize('url', ['http://127.0.0.1/', 'http://192.168.1.2/', 'https://localhost/', 'https://user:pass@example.com/', 'file:///tmp/foo', 'https://10.0.0.1/', 'https://a.local/', 'https://2130706433/'])
def test_replay_urls_match_public_website_contract(url):
    with pytest.raises(HTTPException): source.public_url(url)


@pytest.mark.parametrize('url', ['https://example.com', 'https://replays.example.org/list?page=2'])
def test_public_replay_urls_accepted(url):
    assert source.public_url(url).startswith('https://')


def test_provider_change_cannot_reuse_wrong_password():
    with pytest.raises(HTTPException):
        source.validate_usenet({'usenetProvider':'generic'}, {'usenetProvider':'easynews', 'usenetPassword':'[redacted]'})


def test_section_allowlist_rejects_unrelated_settings():
    with pytest.raises(HTTPException): source.validate_usenet({'debridApiKey':'replacement'}, {})


def test_get_and_patch_auth_metadata_do_not_create_false_conflicts():
    record={'config_id':'config','config':{'enableUsenet':True},'updated_at':'v1'}
    assert source.source_revision(record)==source.source_revision({**record,'has_management_token':True})
    assert source.source_revision(record)!=source.source_revision({**record,'updated_at':'v2'})


def test_custom_indexer_url_normalization_preserves_saved_key():
    patch=source.validate_indexers({'nzbIndexers':[{'type':'custom','url':'https://indexer.example.com','apiKey':''}]},
        {}, {'nzb_indexers':[{'type':'custom','url':'https://indexer.example.com/','api_key':'keep-key'}]})
    assert patch['nzbIndexers'][0]['apiKey']=='keep-key'
    assert patch['nzbIndexers'][0]['url']=='https://indexer.example.com/'


def test_indexer_limit_matches_upstream_without_silent_truncation():
    with pytest.raises(HTTPException):
        source.validate_indexers({'nzbIndexers':[{'type':'custom','url':f'https://index{i}.example.com','apiKey':'key'} for i in range(11)]}, {}, {})


def test_authentication_required():
    app=FastAPI(); app.include_router(source.router)
    app.dependency_overrides[source.get_db] = lambda: None
    assert TestClient(app).get('/me/source-setup/usenet').status_code == 401


def test_new_configuration_saves_app_reference_without_installing_addon(portal, monkeypatch):
    client, _, _, _ = portal
    client.app.dependency_overrides[source.require_premium] = lambda: '00000000-0000-0000-0000-000000000001'
    stored, calls = [], []
    db = SimpleNamespace(add=stored.append, commit=lambda: None)
    client.app.dependency_overrides[source.get_db] = lambda: db
    monkeypatch.setattr(source, 'encrypt_secret', lambda value: value)
    async def upstream(method, path, uid, config_id=None, body=None, bearer=None):
        calls.append((method,path,body))
        if method == 'POST': return {'config_id':'new-config','panda_token':'private-reference','manifest_url':'https://example.invalid/manifest.json'}
        return {'config_id':'new-config','config':{},'updated_at':'v1'}
    monkeypatch.setattr(source, 'upstream', upstream)
    result = client.patch('/me/source-setup/indexers', headers={'X-Torve-Profile-Id':'child'}, json={
        'expectedRevision':source.revision(None),'values':{'nzbIndexers':[{'type':'nzbgeek','url':'','apiKey':'qa-key'}]}})
    assert result.status_code == 200
    assert stored[0].integration_type == 'PANDA_TOKEN::p:child'
    assert stored[0].config['torve_profile_id'] == 'child'
    assert calls[0][2]['enabledProviders'] == []
    assert all('addons' not in c[1] for c in calls)
    assert 'private-reference' not in result.text


def test_real_context_requires_parent_and_existing_profile(monkeypatch):
    from starlette.requests import Request
    from starlette.responses import Response
    db = SimpleNamespace(query=lambda model: SimpleNamespace(filter_by=lambda **kw: SimpleNamespace(first=lambda: None)))
    uid='00000000-0000-0000-0000-000000000001'
    def denied(*args): raise HTTPException(403,'Parent verification required')
    monkeypatch.setattr(source, 'require_parent_when_enabled', denied)
    with pytest.raises(HTTPException) as error:
        source.context(Request({'type':'http','headers':[]}),Response(),uid,db)
    assert error.value.status_code == 403
    monkeypatch.setattr(source, 'require_parent_when_enabled', lambda *args: None)
    with pytest.raises(HTTPException) as error:
        source.context(Request({'type':'http','headers':[(b'x-torve-profile-id',b'unknown')]}),Response(),uid,db)
    assert error.value.status_code == 404


def test_household_replay_record_takes_precedence_and_advances_stamp(portal):
    import json
    client, _, settings, _ = portal
    key='household_v1:default:navigation:web_replays_v1_settings'
    sites=[{'id':'existing-site','name':'Existing library','url':'https://replays.example.com/'}]
    record={'profile':'default','kind':'navigation','id':'web_replays_v1_settings','changedAt':9999999999999,
        'deleted':False,'data':{'value':json.dumps({'enabled':True,'websites':sites})}}
    settings.settings[key]=json.dumps(record)
    settings.settings['web_replays_v1_settings']='{"enabled":false,"websites":[]}'
    loaded=client.get('/me/source-setup/replays').json()
    assert loaded['values']['websites']==sites
    saved=client.put('/me/source-setup/replays',json={'expectedRevision':loaded['revision'],
        'values':{'enabled':True,'websites':[{**sites[0],'enabled':False}]}})
    assert saved.status_code==200
    updated=json.loads(settings.settings[key])
    assert updated['changedAt']==10000000000000
    assert updated['data']['value']==settings.settings['web_replays_v1_settings']
    assert client.get('/me/source-setup/replays').json()['revision']==saved.json()['revision']


def test_household_tombstone_does_not_resurrect_legacy_replays():
    import json
    settings=SimpleNamespace(settings={
        'household_v1:default:navigation:web_replays_v1_settings':json.dumps({'profile':'default','kind':'navigation',
            'id':'web_replays_v1_settings','changedAt':1,'deleted':True,'data':{}}),
        'web_replays_v1_settings':'{"enabled":true,"websites":[{"id":"old"}]}'} )
    assert source.replay_value(settings,'default')[2]=={'enabled':False,'websites':[]}
