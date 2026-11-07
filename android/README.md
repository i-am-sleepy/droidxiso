# XISO Tool - Android app for extract-xiso

An Android app built **around the unmodified upstream `extract-xiso.c`** (it lives one level up,
in the repository root). Nothing in the upstream files is edited, so you can pull upstream
updates with GitHub's **Sync fork** button without merge conflicts.

## What it does

| Screen | What it does |
| --- | --- |
| **Extract ISO** | Unpacks an ISO into a folder named after it, right next to the ISO. |
| **Build ISO** | Turns a folder into `<folder name>.iso`, right next to the folder. |
| **Browse & edit** | Tree view of everything inside an ISO. Extract, delete, replace or add single files; changes are queued and applied in **one rebuild**. |
| **Optimize ISO** | Rewrites an ISO cleanly (upstream's `-r`). |
| **Command line** | Runs the original `extract-xiso` with any of its own options (fallback). |
| **Settings** | What happens to the original after an edit: ask (default) / keep `.bak` / replace / save as new. Media patch, `$SystemUpdate` default, keep screen on. |

It works on your real files with "All files access". Nothing is copied into the app's own storage.
Results are first written as `*.part` / `*.rebuild.part` next to the input and only renamed into place
when complete, so a failure or cancel never damages an existing ISO.

## How it is built

```
extract-xiso.c                      <- upstream, untouched
android/
  app/src/main/cpp/
    xiso_core.c                     #includes extract-xiso.c, adds list/extract/create/rebuild + I/O overlay
    xiso_jni.c                      runs each job in a forked child process (cancellable, crash-safe)
  app/src/main/java/dev/xisotool/   Kotlin + Jetpack Compose UI
  native-test/run_tests.sh          regression tests (verifies results with the ORIGINAL binary)
.github/workflows/android.yml       builds the APK and publishes it under "Releases"
```

How a single-file edit works: the ISO's file tree is loaded with upstream's own reader, the queued
deletes / replacements / additions are applied to that in-memory tree, and upstream's own writer
produces the new ISO. Replacement files are streamed in through a small read overlay, so nothing
is extracted first.

## Building

Push to GitHub. The **Android APK** workflow builds `xiso-tool.apk` and attaches it to a Release.
Run it manually from the *Actions* tab at any time.

## Syncing with upstream later

1. On your fork's page tap **Sync fork -> Update branch**.
2. The workflow runs automatically (it watches `extract-xiso.c`), runs the native tests against the
   new upstream code, and builds a fresh APK.

If a future upstream change breaks the tests, the run summary shows which check failed.

## Running the native tests locally (Linux / macOS with gcc)

```
bash android/native-test/run_tests.sh .
```

## Licence

`extract-xiso.c` is (c) 2003 in <in@fishtank.com> under the licence in `LICENSE.TXT`, which requires
the acknowledgement shown on the app's Settings -> About screen. Keep it when redistributing.
