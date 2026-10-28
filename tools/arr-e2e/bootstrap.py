#!/usr/bin/env python3
"""Configure the legal *Arr E2E stack without persisting or printing API keys."""

from __future__ import annotations

import copy
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


RADARR = "http://radarr:7878"
SONARR = "http://sonarr:8989"
PROWLARR = "http://prowlarr:9696"
BAZARR = "http://bazarr:6767"
TDARR = "http://tdarr:8266"
QBIT = "http://qbit:8080"
QBIT_SEED = "http://qbit-seed:8080"
FIXTURE = "http://fixture:8080"
MOVIE_ROOT = "/data/media/movies"
TDARR_LIBRARY_ID = "torve-e2e-library"


def request(method: str, url: str, api_key: str = "", body=None, timeout: int = 20):
    headers = {"Accept": "application/json"}
    data = None
    if api_key:
        headers["X-Api-Key"] = api_key
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as response:
        payload = response.read()
        if not payload:
            return None
        content_type = response.headers.get("Content-Type", "")
        return json.loads(payload) if "json" in content_type or payload[:1] in (b"{", b"[") else payload.decode("utf-8")


def wait_url(url: str, api_key: str = "", timeout: int = 240):
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        try:
            return request("GET", url, api_key, timeout=5)
        except Exception as exc:  # service boot races are expected
            last_error = exc
            time.sleep(2)
    raise RuntimeError(f"Service did not become ready: {url} ({type(last_error).__name__})")


def api_key(config_path: str) -> str:
    deadline = time.time() + 180
    path = Path(config_path)
    while not path.exists() and time.time() < deadline:
        time.sleep(1)
    root = ET.parse(path).getroot()
    value = root.findtext("ApiKey", "").strip()
    if not value:
        raise RuntimeError(f"No API key was generated in {config_path}")
    return value


def bazarr_api_key(config_path: str) -> str:
    """Read Bazarr's generated key without adding a YAML dependency to bootstrap."""
    deadline = time.time() + 180
    path = Path(config_path)
    while not path.exists() and time.time() < deadline:
        time.sleep(1)
    while time.time() < deadline:
        text = path.read_text(encoding="utf-8")
        auth = re.search(r"(?ms)^auth:\s*$.*?(?=^[A-Za-z_]+:\s*$|\Z)", text)
        key = re.search(r"(?m)^\s{2}apikey:\s*(\S+)", auth.group(0) if auth else "")
        if key:
            return key.group(1)
        time.sleep(1)
    raise RuntimeError(f"No API key was generated in {config_path}")


def request_form(method: str, url: str, api_key_value: str, body: dict, timeout: int = 20):
    data = urllib.parse.urlencode(body, doseq=True).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/x-www-form-urlencoded",
            "X-Api-Key": api_key_value,
        },
        method=method,
    )
    with urllib.request.urlopen(req, timeout=timeout) as response:
        payload = response.read()
        return json.loads(payload) if payload and payload[:1] in (b"{", b"[") else None


def ensure_bazarr_links(bazarr_key: str, radarr_key: str, sonarr_key: str):
    """Attach Bazarr to the same libraries using its UI's supported settings contract."""
    request_form(
        "POST",
        f"{BAZARR}/api/system/settings",
        bazarr_key,
        {
            "settings-general-use_radarr": "true",
            "settings-radarr-ip": "radarr",
            "settings-radarr-port": "7878",
            "settings-radarr-base_url": "/",
            "settings-radarr-apikey": radarr_key,
            "settings-radarr-ssl": "false",
            "settings-general-use_sonarr": "true",
            "settings-sonarr-ip": "sonarr",
            "settings-sonarr-port": "8989",
            "settings-sonarr-base_url": "/",
            "settings-sonarr-apikey": sonarr_key,
            "settings-sonarr-ssl": "false",
        },
    )
    settings = request("GET", f"{BAZARR}/api/system/settings", bazarr_key)
    if not settings.get("general", {}).get("use_radarr") or not settings.get("general", {}).get("use_sonarr"):
        raise RuntimeError("Bazarr did not retain its Radarr/Sonarr connections")


def ensure_bazarr_subtitle_profile(bazarr_key: str, radarr_movie_id: int):
    profile = {
        "profileId": 1,
        "name": "English",
        "tag": None,
        "items": [{
            "id": 1,
            "language": "en",
            "audio_exclude": "False",
            "audio_only_include": "False",
            "hi": "False",
            "forced": "False",
        }],
        "cutoff": 1,
        "mustContain": [],
        "mustNotContain": [],
        "originalFormat": False,
    }
    request_form(
        "POST",
        f"{BAZARR}/api/system/settings",
        bazarr_key,
        {
            "languages-enabled": ["en"],
            "languages-profiles": json.dumps([profile]),
            "settings-general-enabled_providers": ["embeddedsubtitles"],
            "settings-embeddedsubtitles-timeout": "60",
            "settings-embeddedsubtitles-hi_fallback": "false",
            "settings-embeddedsubtitles-unknown_as_fallback": "false",
            "settings-embeddedsubtitles-fallback_lang": "en",
        },
    )

    deadline = time.time() + 120
    while time.time() < deadline:
        movies = request("GET", f"{BAZARR}/api/movies?start=0&length=200", bazarr_key) or {}
        if any(row.get("radarrId") == radarr_movie_id for row in movies.get("data", [])):
            break
        time.sleep(2)
    else:
        raise RuntimeError("Bazarr did not synchronize the Radarr movie")

    request_form(
        "POST",
        f"{BAZARR}/api/movies",
        bazarr_key,
        {"radarrid": str(radarr_movie_id), "profileid": "1"},
    )
    deadline = time.time() + 60
    while time.time() < deadline:
        wanted = request("GET", f"{BAZARR}/api/movies/wanted?start=0&length=200", bazarr_key) or {}
        if any(row.get("radarrId") == radarr_movie_id for row in wanted.get("data", [])):
            return
        time.sleep(2)
    raise RuntimeError("Bazarr did not expose the movie in its wanted-subtitles queue")


def schema_payload(schema: dict, name: str, fields: dict, **top_level) -> dict:
    payload = copy.deepcopy(schema)
    payload["name"] = name
    payload.update(top_level)
    for field in payload.get("fields", []):
        field_name = field.get("name")
        if field_name in fields:
            field["value"] = fields[field_name]
    return payload


def ensure_schema_resource(
    base: str,
    version: str,
    key: str,
    resource: str,
    implementation: str,
    name: str,
    fields: dict,
    preferred_schema_name: str | None = None,
    **top_level,
) -> dict:
    existing = request("GET", f"{base}/api/{version}/{resource}", key) or []
    found = next((row for row in existing if row.get("name") == name), None)
    if found:
        return found
    schemas = request("GET", f"{base}/api/{version}/{resource}/schema", key) or []
    schema = None
    if preferred_schema_name:
        schema = next((row for row in schemas if row.get("name") == preferred_schema_name), None)
    schema = schema or next((row for row in schemas if row.get("implementation") == implementation), None)
    if not schema:
        raise RuntimeError(f"{resource} schema {implementation} is unavailable")
    payload = schema_payload(schema, name, fields, **top_level)
    return request("POST", f"{base}/api/{version}/{resource}", key, payload)


def ensure_root(base: str, key: str, path: str) -> dict:
    roots = request("GET", f"{base}/api/v3/rootfolder", key) or []
    found = next((row for row in roots if row.get("path") == path), None)
    return found or request("POST", f"{base}/api/v3/rootfolder", key, {"path": path})


def qbit_add_torrent(base: str, torrent_path: Path, save_path: str):
    boundary = "----torve-e2e-boundary"
    torrent = torrent_path.read_bytes()
    chunks = []
    for name, value in (("savepath", save_path), ("paused", "false"), ("skip_checking", "false")):
        chunks.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode())
    chunks.append(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"torrents\"; filename=\"sample.torrent\"\r\nContent-Type: application/x-bittorrent\r\n\r\n".encode()
        + torrent
        + b"\r\n",
    )
    chunks.append(f"--{boundary}--\r\n".encode())
    req = urllib.request.Request(
        f"{base}/api/v2/torrents/add",
        data=b"".join(chunks),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            response.read()
    except urllib.error.HTTPError as exc:
        if exc.code != 409:  # already present is idempotent success
            raise


def ensure_movie(radarr_key: str, quality_profile_id: int) -> dict:
    movies = request("GET", f"{RADARR}/api/v3/movie", radarr_key) or []
    found = next((row for row in movies if row.get("tmdbId") == 10378), None)
    if found:
        return found
    lookup_url = f"{RADARR}/api/v3/movie/lookup?{urllib.parse.urlencode({'term': 'tmdb:10378'})}"
    candidates = request("GET", lookup_url, radarr_key) or []
    if not candidates:
        raise RuntimeError("Radarr could not resolve the CC-licensed Big Buck Bunny metadata fixture")
    candidate = copy.deepcopy(candidates[0])
    candidate.pop("id", None)
    candidate.update(
        {
            "qualityProfileId": quality_profile_id,
            "rootFolderPath": MOVIE_ROOT,
            "monitored": True,
            "minimumAvailability": "released",
            "addOptions": {"searchForMovie": False},
        },
    )
    return request("POST", f"{RADARR}/api/v3/movie", radarr_key, candidate)


def tdarr_call(body):
    return request("POST", f"{TDARR}/api/v2/cruddb", body=body)


def ensure_tdarr_library():
    query = {"data": {"collection": "LibrarySettingsJSONDB", "mode": "getById", "docID": TDARR_LIBRARY_ID}}
    try:
        found = tdarr_call(query)
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            raise
        found = None
    schedule = []
    for day in ("Sun", "Mon", "Tue", "Wed", "Thur", "Fri", "Sat"):
        for hour in range(24):
            schedule.append({"_id": f"{day}:{hour:02d}-{(hour + 1) % 24:02d}", "checked": True})
    plugin = {
        "_id": "torve-e2e-cpu-plugin",
        "id": "Tdarr_Plugin_MC93_Migz1FFMPEG_CPU",
        "source": "Community",
        "priority": 0,
        "checked": True,
        "InputsDB": {"container": "mkv", "bitrate_cutoff": "", "enable_10bit": False, "force_conform": False},
    }
    library = {
        "_id": TDARR_LIBRARY_ID,
        "priority": 1,
        "name": "Torve E2E imported movies",
        "folder": "/media/movies",
        "foldersToIgnore": "",
        "foldersToIgnoreCaseInsensitive": False,
        "folderWatchScanInterval": 30,
        "scannerThreadCount": 2,
        "cache": "/temp",
        "output": "/media/transcoded",
        "folderToFolderConversion": True,
        "folderToFolderConversionDeleteSource": False,
        "folderToFolderRecordHistory": True,
        "copyIfConditionsMet": False,
        "container": ".mkv",
        "containerFilter": "mkv,mp4,mov,m4v,mpg,mpeg,avi,flv,webm,wmv,vob,evo,iso,m2ts,ts",
        "folderWatching": False,
        "useFsEvents": False,
        "scheduledScanFindNew": False,
        "processLibrary": True,
        "processTranscodes": True,
        "processHealthChecks": False,
        "scanOnStart": False,
        "exifToolScan": False,
        "mediaInfoScan": False,
        "ffprobeShowData": False,
        "isDirectoryLibrary": False,
        "closedCaptionScan": False,
        "scanButtons": False,
        "scanFound": "",
        "navItemSelected": "navSourceFolder",
        "pluginIDs": [plugin],
        "pluginCommunity": True,
        "handbrake": False,
        "ffmpeg": True,
        "handbrakescan": False,
        "ffmpegscan": True,
        "preset": '-Z "Very Fast 1080p30"',
        "decisionMaker": {
            "settingsPlugin": True,
            "settingsFlows": False,
            "settingsVideo": False,
            "settingsAudio": False,
        },
        "schedule": schedule,
        "totalHealthCheckCount": 0,
        "totalTranscodeCount": 0,
        "sizeDiff": 0,
        "holdNewFiles": False,
        "holdFor": 3600,
        "holdForDisplayUnit": "hours",
        "pluginStackOverview": True,
        "filterResolutionsSkip": "",
        "filterCodecsSkip": "",
        "filterContainersSkip": "",
        "filterHardlinked": False,
        "processPluginsSequentially": False,
    }
    mode = "update" if found else "insert"
    return tdarr_call(
        {"data": {"collection": "LibrarySettingsJSONDB", "mode": mode, "docID": TDARR_LIBRARY_ID, "obj": library}},
    )


def main():
    for directory in ("/state/media/movies", "/state/media/tv", "/state/media/transcoded", "/state/downloads"):
        path = Path(directory)
        path.mkdir(parents=True, exist_ok=True)
        os.chmod(path, 0o777)
    radarr_key = api_key("/arr-config/radarr/config.xml")
    sonarr_key = api_key("/arr-config/sonarr/config.xml")
    prowlarr_key = api_key("/arr-config/prowlarr/config.xml")
    bazarr_key = bazarr_api_key("/bazarr-config/config/config.yaml")
    wait_url(f"{RADARR}/api/v3/system/status", radarr_key)
    wait_url(f"{SONARR}/api/v3/system/status", sonarr_key)
    wait_url(f"{PROWLARR}/api/v1/system/status", prowlarr_key)
    wait_url(f"{BAZARR}/api/system/status", bazarr_key)
    wait_url(f"{TDARR}/api/v2/status")
    wait_url(f"{FIXTURE}/health")
    wait_url(f"{QBIT}/api/v2/app/version")
    wait_url(f"{QBIT_SEED}/api/v2/app/version")

    qbit_add_torrent(QBIT_SEED, Path("/fixture/sample.torrent"), "/seed")
    ensure_root(RADARR, radarr_key, MOVIE_ROOT)
    ensure_root(SONARR, sonarr_key, "/data/media/tv")

    qbit_fields = {
        "host": "qbit",
        "port": 8080,
        "useSsl": False,
        "urlBase": "",
        "username": "",
        "password": "",
        "movieCategory": "radarr",
        "tvCategory": "sonarr",
        "category": "radarr",
        "recentMoviePriority": 0,
        "olderMoviePriority": 0,
        "recentTvPriority": 0,
        "olderTvPriority": 0,
        "initialState": 0,
    }
    ensure_schema_resource(
        RADARR, "v3", radarr_key, "downloadclient", "QBittorrent", "Torve E2E qBittorrent", qbit_fields,
        enable=True, priority=1, removeCompletedDownloads=True, removeFailedDownloads=True,
    )
    ensure_schema_resource(
        SONARR, "v3", sonarr_key, "downloadclient", "QBittorrent", "Torve E2E qBittorrent", qbit_fields,
        enable=True, priority=1, removeCompletedDownloads=True, removeFailedDownloads=True,
    )

    indexer_fields = {
        "baseUrl": FIXTURE,
        "apiPath": "/api",
        "apiKey": "",
        "additionalParameters": "",
        "torrentBaseSettings.appMinimumSeeders": 1,
    }
    for base, version, key in ((PROWLARR, "v1", prowlarr_key), (RADARR, "v3", radarr_key), (SONARR, "v3", sonarr_key)):
        service_fields = {"appProfileId": 1} if base == PROWLARR else {}
        ensure_schema_resource(
            base, version, key, "indexer", "Torznab", "Torve Legal Fixture", indexer_fields,
            preferred_schema_name="Generic Torznab", enableRss=True, enableAutomaticSearch=True,
            enableInteractiveSearch=True, priority=1, **service_fields,
        )

    profiles = request("GET", f"{RADARR}/api/v3/qualityprofile", radarr_key) or []
    if not profiles:
        raise RuntimeError("Radarr has no quality profile")
    movie = ensure_movie(radarr_key, profiles[0]["id"])
    ensure_bazarr_links(bazarr_key, radarr_key, sonarr_key)
    ensure_bazarr_subtitle_profile(bazarr_key, movie["id"])
    ensure_tdarr_library()

    summary = {
        "ready": True,
        "movieId": movie["id"],
        "tmdbId": movie.get("tmdbId"),
        "tdarrLibraryId": TDARR_LIBRARY_ID,
        "release": "generated color bars and tone",
        "services": ["sonarr", "radarr", "prowlarr", "bazarr", "tdarr", "tdarr-node", "qbittorrent", "torznab-tracker"],
    }
    Path("/state/bootstrap-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, sort_keys=True))


if __name__ == "__main__":
    main()
