# PiPhotos — Gemini CLI Operating Instructions

This file configures Gemini CLI for development on the **PiPhotos** repository.

## Core Directives

1. **Test Before Complete**:
   - Run `./gradlew testDebugUnitTest` to verify any behavioral changes.
   - Tag all actions with `[UNIT-TESTED]`, `[CODE-ONLY]`, or `[NEEDS-DEVICE-CHECK]`.
2. **Zero Banned Tokens**:
   - Comply with `ReleaseLeakCheckTest`. Do not include demo hardware, personal names, or secret strings in `app/src/main`.
   - Run `./gradlew assembleRelease verifyReleaseNoLeaks`.
3. **120Hz UI & Coroutines**:
   - Keep Main thread free of IO (`Dispatchers.IO` for Room / network / files).
   - Use structured concurrency (`viewModelScope`, `lifecycleScope`).
4. **Follow Clean Architecture**:
   - Follow rules and skills transplanted into `.agents/rules/` and `.agents/skills/`.
   - Update knowledge graph with `graphify update .` after editing code.
