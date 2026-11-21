# PiPhotos Performance Baselines

Performance targets and measurements for the Motorola Edge 50 Neo (120 Hz, 1200×2670 px,
Android 14, adb serial `ZD222P9BBZ`).

## Targets

| Metric | Target |
|--------|--------|
| Cold start (first grid frame) | < 1 000 ms |
| Janky frames at 120 Hz | < 2 % |
| First thumbnail visible | < 150 ms |
| Viewer open to first pixel | < 100 ms |
| Peak heap while scrolling | < 150 MB |
| Release APK size | < 15 MB |

---

## Baseline Measurements (Phase P1 — 2026-11-20)

| Metric | Baseline | Build | Method | Runs |
|--------|----------|-------|--------|------|
| Cold start (first grid frame) | **1 078 ms** (median) | debug | `am start -W` / `cold_start.ps1` | 9 |
| Janky frames at 120 Hz | [NEEDS-DEVICE-CHECK] | debug | `gfxinfo` after 5 flings / `jank_stats.ps1` | — |
| First thumbnail visible | [NEEDS-DEVICE-CHECK] | debug | Perfetto `ThumbnailLoader.decode` trace / `startup_trace.ps1` | — |
| Viewer open to first pixel | [NEEDS-DEVICE-CHECK] | debug | Perfetto `PhotoViewer.onCreate` trace / `startup_trace.ps1` | — |
| Peak heap while scrolling | [NEEDS-DEVICE-CHECK] | debug | `dumpsys meminfo` after fling / `memory_info.ps1` | — |
| Release APK size | **4.54 MB** ✅ | release | `apk_size.ps1` | 1 |

> **Cold start runs (raw TotalTime values):**
> 1342, 1353, 690\*, 1089, 1114, [skip], 1061, 1067, 1078, 1058 ms
> \* 690 ms is an outlier (warm process from OS). Excluded from median.
> Median of remaining 8 runs: **1 078 ms** — above 1 000 ms target → target for P3 optimisation.

---

## How to Re-Run Measurements

```powershell
# Cold start (10 runs, reports median)
powershell -File scripts/perf/cold_start.ps1

# Jank at 120 Hz (5 flings, reports jank %)
powershell -File scripts/perf/jank_stats.ps1

# Peak heap RSS
powershell -File scripts/perf/memory_info.ps1

# APK size breakdown
powershell -File scripts/perf/apk_size.ps1

# Perfetto trace (startup + thumbnail + viewer open)
powershell -File scripts/perf/startup_trace.ps1
# → open the downloaded .perfetto-trace file at https://ui.perfetto.dev
```

Macrobenchmark and baseline-profile *generation*: **not implemented yet.** There is no `:macrobenchmark`
or `:baselineprofile` module in `settings.gradle.kts`, so there is no Gradle command for them.
`app/src/main/baseline-prof.txt` is a short hand-written list of hot classes, not a generated profile.
Until those modules exist, measure with the scripts above on a release build.

---

## After Each Phase

Only the P1 baseline was measured. The later phases changed code but **no after-numbers have been
measured yet**; do not read them as improvements until a row below has real values.

| Phase | Cold start | Jank | APK | Heap | Notes |
|-------|-----------|------|-----|------|-------|
| P1 baseline | 1 078 ms | not measured | 4.54 MB | not measured | debug build; JankStats, trace markers added |
| P2 | not measured | not measured | not measured | not measured | header index lookups, prefetch throttling during flings |
| P3 | not measured | not measured | not measured | not measured | lazy tab fragments, hand-written baseline-prof.txt |
| P4 | not measured | not measured | 4.55 MB (reported) | not measured | arm64-v8a ABI filter, R8 full mode |
| P5 | not measured | not measured | not measured | not measured | OkHttp client reuse, WAL synchronous=NORMAL, bench scripts |

## Known weaknesses of the baseline

- It was taken on a **debug** build; the targets are for release.
- The cold-start median excluded the fastest run (690 ms) as "warm". Run `adb shell am force-stop` before
  every run instead of dropping values, and report all values.
- Jank, first thumbnail, viewer open and heap have no baseline at all yet.
