# PiPhotos — Agent Engineering Guide & Operating Rules

This repository contains **PiPhotos**, a privacy-first, high-performance Android photo gallery and self-hosted backup client paired with private local vaults.

---

## 1. Core Principles

1. **Test-Driven Development (TDD)**:
   - For bug fixes and new logic, write failing tests first, make them pass minimally, then refactor.
   - Run tests: `./gradlew testDebugUnitTest`.
2. **Verification Loop**:
   - Verify every change before claiming completion.
   - Clearly tag verification status:
     - `[UNIT-TESTED]`: Verified with local Gradle unit tests.
     - `[CODE-ONLY]`: Pure code/documentation change without test suite execution.
     - `[NEEDS-DEVICE-CHECK]`: Requires physical Android device or emulator run (e.g., UI interactions, camera, hardware decoders).
3. **Zero Leaks Policy (`verifyReleaseNoLeaks`)**:
   - Absolutely NO hardcoded demo names, test credentials, internal IPs, or banned leak strings in `app/src/main` or release APK.
   - Run verification: `./gradlew assembleRelease verifyReleaseNoLeaks`.
4. **Immutability & Defensive Typing**:
   - Default to `val` and immutable collections (`List`, `Set`, `Map`).
   - Use `data class` for domain entities and UI state.
   - Use sealed interfaces for state modelling (`Loading`, `Success`, `Error`) with exhaustive `when` (no default `else`).
   - Never use `!!` force unwrap; use `?.`, `?:`, `requireNotNull()`, or `checkNotNull()`.
5. **Knowledge Graph Synchronization**:
   - Keep the project knowledge graph current. After modifying code files, run `graphify update .`.

---

## 2. Architecture & Performance Standards

### 120Hz Smooth Scrolling UI
- Main thread budget is **8.3ms per frame**.
- Zero disk or network IO on `Dispatchers.Main`. All database and file operations must use `Dispatchers.IO`.
- Single-pass layout: avoid deep nested layouts with `layout_weight` that cause quadratic measurement overhead.
- RecyclerView & ViewHolder efficiency:
  - Cache view lookups (ViewBinding or ViewHolder references).
  - Offload bitmap decoding and downsampling to Coil's background pipeline.
  - Implement smooth item diffing with `DiffUtil.ItemCallback` on background threads.

### Clean Architecture & Data Flow
```
UI (Activity / Fragment / View)
       ↓ (Events)
  ViewModel (StateFlow / SharedFlow)
       ↓ (Invoke)
   UseCase (Single Responsibility Business Logic)
       ↓ (Repository Interface)
  Repository Implementation
   ├── LocalDataSource (Room DB, SharedPreferences)
   └── RemoteDataSource (Ktor / OkHttp Streaming to Vault)
```

### Content-Addressable Storage (CAS)
- Deduplication via streaming SHA-256 hashing.
- Chunked 64KB buffer streaming for upload and download — never load entire high-res images/videos into heap memory.
- Graceful disconnect handling and chunked resume capabilities.

### Background Work & Reliability
- Use `WorkManager` with exponential backoff and network constraints for media backup jobs.
- Validate upload conditions (Wi-Fi, charging, battery level) before initiating large payload transfers.
- Freeze upload conditions during active transfers to prevent race conditions or corrupted socket state.

---

## 3. Specialized Subagents & Workflows

Available in `.agents/`:
- **Rules**: `.agents/rules/` (`kotlin-*`, `common-*`, `graphify.md`)
- **Skills**: `.agents/skills/`
  - `android-clean-architecture`
  - `kotlin-coroutines-flows`
  - `kotlin-patterns`
  - `kotlin-testing`
  - `kotlin-ktor-patterns`
  - `tdd-workflow`
  - `verification-loop`
  - `security-review`
  - `graphify`
- **Agents**: `.agents/agents/`
  - `kotlin-reviewer`: Idiomatic patterns, coroutines, and Compose/Android review.
  - `kotlin-build-resolver`: Gradle and Kotlin compiler error resolution.
  - `code-reviewer`: General code quality and maintainability review.
  - `security-reviewer`: Vulnerability scan and permissions audit.
  - `tdd-guide`: Step-by-step TDD cycle implementation.
  - `performance-optimizer`: 120Hz frame budget and memory leak prevention.
- **Workflows**: `.agents/workflows/`
  - `kotlin-build.md`, `kotlin-test.md`, `gradle-build.md`, `quality-gate.md`, `refactor-clean.md`

---

## 4. Verification & Build Commands

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Run release leak verification
./gradlew assembleRelease verifyReleaseNoLeaks

# Lint & style check
./gradlew lintDebug

# Knowledge graph update
graphify update .
```
