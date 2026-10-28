Torve supports English, German, Spanish, French, Italian, Portuguese and Turkish.

Run `python tools/localization/check_catalogs.py` from the repository. It checks Android phone/TV and Apple resource coverage, duplicate keys, format argument positions/types and literal percentage signs. It requires only Python's standard library.

On the configured Mac, build iOS with `SWIFT_EMIT_LOC_STRINGS=YES`, then run `python3 tools/localization/check_apple_compiler_keys.py <Objects-normal/arm64-directory>`. This also checks native SwiftUI overloads, including interpolated buttons, accessibility labels and search prompts that a literal-source scan can miss.

`reviewed-copy.json` preserves copy corrections and names such as Home, provider brands and video resolutions. The normal Apple generator applies these after older overrides and preserves Apple-only keys and typed format contracts.

App-owned String properties use `parityLabel` / `tvSettingsLabel` on Android and `AppleUIText` / `AppleUILabel` on Apple. Exact catalog keys and complete known sentence templates are supported. Provider names, media titles, comments, profile names and entered addresses must remain outside these bridges. Metadata titles and artwork use the existing language-aware media enrichment path and retain the provider fallback when localized metadata is unavailable.

The October 28 audit's parser reports, seven-language native screenshots, device checks, formatter checks and build/delivery receipts are recorded under `output/android-full-localization-2026-10-28`. Static resource coverage does not replace native layout checks or a linguistic review of newly added copy.
