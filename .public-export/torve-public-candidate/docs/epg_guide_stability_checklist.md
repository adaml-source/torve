# Fire TV EPG Stability Checklist

## Repro Steps
1. Launch app on Fire TV with at least one playlist that has XMLTV data.
2. Open TV IPTV guide and move focus across multiple channels and pages.
3. Force refresh guide once from settings, then reopen guide.
4. Repeat channel/category navigation for 2-3 minutes.

## Expected Logs
- `ChannelsEPG: got response object ... status=<code>` (proves `get()` returned)
- `ChannelsEPG: db ingest complete ... generation=<id>`
- `ChannelsEPG: cache read ... generation=<id> ... groupedKeys=<n>`
- `ChannelsEPG: mapped ... generation=<id> guideChannels=<n> mappedKeys=<n>`
- `ChannelsEPG: downloadToTempFile ENTER ...`
- `ChannelsEPG: downloadToTempFile DONE ...`
- No fuzzy-match logs, no `distinctBy`-style remap behavior, no per-row key scan logs.

## Expected Memory/Allocation Behavior
- Guide lookup is strict `epg_channel_key` map access only.
- No `guideProgrammes.entries` scans for matching.
- No per-channel sort/distinct rebuild during guide map creation.
- DB window rows are consumed in `(epg_channel_key, start_time)` order and grouped in one pass.
- Lower churn while scrolling guide (fewer transient lists and normalized-key allocations).

## Device-Targeted Logcat (Fire TV)
Use a specific serial so logs do not come from an emulator by accident.

```powershell
adb devices
adb -s <fire_tv_serial> logcat -v time | findstr ChannelsEPG
```

Optional focused command:

```powershell
adb -s <fire_tv_serial> logcat -v time | findstr /I "ChannelsEPG OutOfMemoryError EpgStreamLimitException"
```

## Local Safety Check
Run this before QA to catch accidental full-buffer APIs in the EPG path:

```powershell
./gradlew :shared:checkEpgStreamingSafety
```
