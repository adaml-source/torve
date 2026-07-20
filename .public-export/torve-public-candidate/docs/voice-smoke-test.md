# Voice Smoke Test

## Build

```bash
./gradlew :androidApp:assembleMobileDebug
./gradlew :androidApp:assembleTvDebug
```

## Mobile Search Voice

- Open Search on mobile.
- Tap the mic button and speak a query.
- Verify the query field updates and results load.
- Verify state text appears during capture:
  - `Listening`
  - `Processing voice input`
- On a device without voice recognition, verify fallback text appears and there is no crash.

## TV Search Voice

- Open `Search` on TV.
- Move focus to the mic action using D-pad.
- Trigger voice input and speak a query.
- Verify the query populates and results refresh.
- Verify fallback behavior when voice recognition is unavailable.

## Player Voice Commands

- Start playback and open controls.
- Use voice input and validate:
  - `pause` pauses playback
  - `play` or `resume` resumes playback
  - `forward` seeks +10s
  - `rewind` seeks -10s
  - `search for <query>` navigates to Search with that query
- Verify confirmation overlay appears for each accepted command.
- Verify unrecognized commands show a non-crashing feedback message.
