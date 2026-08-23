#!/usr/bin/env python3
"""Build a small, deterministic XMLTV feed from selected EPGShare feeds.

The merger streams every gzip input and writes channels/programmes to separate
temporary spools. This keeps XMLTV's channel-before-programme ordering without
holding the complete source documents in memory. Existing output is replaced
only after every source has downloaded, parsed, and produced programme data.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import shutil
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Iterable

try:
    import fcntl
except ImportError:  # The production job runs on Linux; unit tests also run on Windows.
    fcntl = None


EPGSHARE_BASE_URL = "https://epgshare01.online/epgshare01"
DOWNLOAD_LIMIT_BYTES = 100 * 1024 * 1024
CHUNK_BYTES = 256 * 1024


@dataclass(frozen=True)
class FeedSpec:
    filename: str
    # None imports the complete country/service feed. A set imports only the
    # named channel IDs, which is used for Ukrainian channels distributed
    # across neighbouring-country/service feeds.
    channel_ids: frozenset[str] | None = None


FEEDS = (
    FeedSpec("epg_ripper_DE1.xml.gz"),
    FeedSpec("epg_ripper_HU1.xml.gz"),
    FeedSpec("epg_ripper_UK1.xml.gz"),
    FeedSpec("epg_ripper_US2.xml.gz"),
    FeedSpec("epg_ripper_US_LOCALS1.xml.gz"),
    FeedSpec("epg_ripper_US_SPORTS1.xml.gz"),
    FeedSpec("epg_ripper_IT1.xml.gz"),
    FeedSpec("epg_ripper_AT1.xml.gz"),
    FeedSpec("epg_ripper_CH1.xml.gz", frozenset({"1+1.Ukraine.ch"})),
    FeedSpec(
        "epg_ripper_CZ1.xml.gz",
        frozenset({"1+1.Ukraina.cz", "Ukraine.1.cz", "Ukraine.2.cz"}),
    ),
    FeedSpec("epg_ripper_MUSICBOX1.xml.gz", frozenset({"MUSIC.BOX.UKRAINE.musicbox"})),
    FeedSpec("epg_ripper_NL1.xml.gz", frozenset({"Nickelodeon.Ukraine.nl"})),
    FeedSpec(
        "epg_ripper_PL1.xml.gz",
        frozenset({"Ukraina.1.HD.pl", "Ukraina.1.pl", "Ukraina.24.pl", "Ukraina.2.pl"}),
    ),
    FeedSpec("epg_ripper_VOA1.xml.gz", frozenset({"UKRAINIAN.CHANNEL.-.VOA.voa"})),
)


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def programme_identity(element: ET.Element) -> bytes:
    title = next(
        (
            (child.text or "").strip()
            for child in element
            if local_name(child.tag) == "title"
        ),
        "",
    )
    stable = "\0".join(
        (
            element.attrib.get("channel", "").strip(),
            element.attrib.get("start", "").strip(),
            element.attrib.get("stop", "").strip(),
            title,
        )
    )
    return hashlib.blake2b(stable.encode("utf-8"), digest_size=16).digest()


def write_element(output: BinaryIO, element: ET.Element) -> None:
    output.write(b"  ")
    output.write(ET.tostring(element, encoding="utf-8", short_empty_elements=True))
    output.write(b"\n")


def merge_feed_files(
    source_files: Iterable[tuple[FeedSpec, Path]],
    output_path: Path,
) -> dict[str, object]:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    channel_ids: set[str] = set()
    programme_ids: set[bytes] = set()
    source_stats: list[dict[str, object]] = []

    with tempfile.TemporaryDirectory(prefix="torve-epg-spool-") as spool_dir_name:
        spool_dir = Path(spool_dir_name)
        channels_spool = spool_dir / "channels.xml"
        programmes_spool = spool_dir / "programmes.xml"

        with channels_spool.open("wb") as channels_output, programmes_spool.open("wb") as programmes_output:
            for spec, source_path in source_files:
                source_channels = 0
                source_programmes = 0
                allowed = spec.channel_ids
                with gzip.open(source_path, "rb") as xml_input:
                    context = ET.iterparse(xml_input, events=("start", "end"))
                    _, root = next(context)
                    for event, element in context:
                        if event != "end":
                            continue
                        name = local_name(element.tag)
                        if name == "channel":
                            channel_id = element.attrib.get("id", "").strip()
                            if (
                                channel_id
                                and (allowed is None or channel_id in allowed)
                                and channel_id not in channel_ids
                            ):
                                channel_ids.add(channel_id)
                                write_element(channels_output, element)
                                source_channels += 1
                            element.clear()
                            root.clear()
                        elif name == "programme":
                            channel_id = element.attrib.get("channel", "").strip()
                            if allowed is None or channel_id in allowed:
                                identity = programme_identity(element)
                                if identity not in programme_ids:
                                    programme_ids.add(identity)
                                    write_element(programmes_output, element)
                                    source_programmes += 1
                            element.clear()
                            root.clear()
                source_stats.append(
                    {
                        "source": spec.filename,
                        "channels_added": source_channels,
                        "programmes_added": source_programmes,
                        "filtered": allowed is not None,
                    }
                )

        if not channel_ids or not programme_ids:
            raise RuntimeError("Combined EPG did not contain both channels and programmes; previous output retained.")

        temporary_output = output_path.with_name(f".{output_path.name}.{os.getpid()}.tmp")
        try:
            with temporary_output.open("wb") as raw_output:
                with gzip.GzipFile(fileobj=raw_output, mode="wb", compresslevel=6, mtime=0) as output:
                    output.write(b'<?xml version="1.0" encoding="UTF-8"?>\n')
                    output.write(
                        b'<tv generator-info-name="Torve selected EPG merge" '
                        b'source-info-url="https://epgshare01.online/epgshare01/">\n'
                    )
                    with channels_spool.open("rb") as source:
                        shutil.copyfileobj(source, output, CHUNK_BYTES)
                    with programmes_spool.open("rb") as source:
                        shutil.copyfileobj(source, output, CHUNK_BYTES)
                    output.write(b"</tv>\n")
            os.chmod(temporary_output, 0o644)
            os.replace(temporary_output, output_path)
        finally:
            temporary_output.unlink(missing_ok=True)

    return {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "duration_seconds": round(time.monotonic() - started, 3),
        "channels": len(channel_ids),
        "programmes": len(programme_ids),
        "compressed_bytes": output_path.stat().st_size,
        "sources": source_stats,
    }


def download_feed(spec: FeedSpec, destination: Path) -> None:
    request = urllib.request.Request(
        f"{EPGSHARE_BASE_URL}/{spec.filename}",
        headers={"User-Agent": "Torve-EPG-Merger/1.0", "Accept-Encoding": "identity"},
    )
    with urllib.request.urlopen(request, timeout=180) as response, destination.open("wb") as output:
        content_length = response.headers.get("Content-Length")
        if content_length and int(content_length) > DOWNLOAD_LIMIT_BYTES:
            raise RuntimeError(f"Source {spec.filename} exceeds the per-feed compressed safety limit.")
        downloaded = 0
        while True:
            chunk = response.read(CHUNK_BYTES)
            if not chunk:
                break
            downloaded += len(chunk)
            if downloaded > DOWNLOAD_LIMIT_BYTES:
                raise RuntimeError(f"Source {spec.filename} exceeded the per-feed compressed safety limit.")
            output.write(chunk)
    with destination.open("rb") as downloaded_file:
        if downloaded_file.read(2) != b"\x1f\x8b":
            raise RuntimeError(f"Source {spec.filename} is not a gzip payload.")


def atomic_write_json(path: Path, value: dict[str, object]) -> None:
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def build(output_path: Path, feeds: tuple[FeedSpec, ...] = FEEDS) -> dict[str, object]:
    if fcntl is None:
        raise RuntimeError("Scheduled EPG builds require a POSIX file lock.")
    lock_path = output_path.with_suffix(output_path.suffix + ".lock")
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("w", encoding="utf-8") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        with tempfile.TemporaryDirectory(prefix="torve-epg-download-") as download_dir_name:
            download_dir = Path(download_dir_name)
            source_files: list[tuple[FeedSpec, Path]] = []
            for spec in feeds:
                destination = download_dir / spec.filename
                download_feed(spec, destination)
                source_files.append((spec, destination))
            stats = merge_feed_files(source_files, output_path)
            atomic_write_json(output_path.with_suffix(output_path.suffix + ".json"), stats)
            return stats


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--profile",
        choices=("full", "core", "us-locals"),
        default="full",
        help="core omits the very large US local-stations bundle",
    )
    args = parser.parse_args()
    feeds = {
        "full": FEEDS,
        "core": tuple(feed for feed in FEEDS if feed.filename != "epg_ripper_US_LOCALS1.xml.gz"),
        "us-locals": tuple(feed for feed in FEEDS if feed.filename == "epg_ripper_US_LOCALS1.xml.gz"),
    }[args.profile]
    stats = build(args.output.resolve(), feeds)
    print(json.dumps(stats, sort_keys=True))


if __name__ == "__main__":
    main()
