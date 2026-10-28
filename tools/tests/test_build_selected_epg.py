import gzip
import importlib.util
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


TOOL_PATH = Path(__file__).parents[1] / "build-selected-epg.py"
SPEC = importlib.util.spec_from_file_location("torve_epg_merge", TOOL_PATH)
assert SPEC and SPEC.loader
epg_merge = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = epg_merge
SPEC.loader.exec_module(epg_merge)


def _fixture(path: Path, channels: list[str], programmes: list[tuple[str, str]]) -> None:
    with gzip.open(path, "wt", encoding="utf-8") as output:
        output.write('<?xml version="1.0" encoding="UTF-8"?><tv>')
        for channel_id in channels:
            output.write(f'<channel id="{channel_id}"><display-name>{channel_id}</display-name></channel>')
        for channel_id, title in programmes:
            output.write(
                f'<programme channel="{channel_id}" start="20260823150000 +0000" '
                f'stop="20260823160000 +0000"><title>{title}</title></programme>'
            )
        output.write("</tv>")


def test_streaming_merge_filters_ukraine_and_deduplicates(tmp_path):
    country = tmp_path / "country.xml.gz"
    regional = tmp_path / "regional.xml.gz"
    output = tmp_path / "combined.xml.gz"
    _fixture(country, ["de.one"], [("de.one", "News"), ("de.one", "News")])
    _fixture(
        regional,
        ["Ukraine.1.cz", "unrelated.cz"],
        [("Ukraine.1.cz", "Ukraine News"), ("unrelated.cz", "Other")],
    )

    stats = epg_merge.merge_feed_files(
        [
            (epg_merge.FeedSpec(country.name), country),
            (
                epg_merge.FeedSpec(regional.name, frozenset({"Ukraine.1.cz"})),
                regional,
            ),
        ],
        output,
    )

    with gzip.open(output, "rb") as merged:
        root = ET.parse(merged).getroot()
    assert [entry.attrib["id"] for entry in root.findall("channel")] == ["de.one", "Ukraine.1.cz"]
    assert [entry.attrib["channel"] for entry in root.findall("programme")] == ["de.one", "Ukraine.1.cz"]
    assert stats["channels"] == 2
    assert stats["programmes"] == 2


def test_core_profile_excludes_only_us_local_stations():
    core = tuple(
        feed for feed in epg_merge.FEEDS
        if feed.filename != "epg_ripper_US_LOCALS1.xml.gz"
    )
    assert any(feed.filename == "epg_ripper_US2.xml.gz" for feed in core)
    assert any(feed.filename == "epg_ripper_US_SPORTS1.xml.gz" for feed in core)
    assert not any(feed.filename == "epg_ripper_US_LOCALS1.xml.gz" for feed in core)
