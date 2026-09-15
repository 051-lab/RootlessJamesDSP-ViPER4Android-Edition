# Baseline validation and LiveProg comparison

## Verdict

The debug application builds, the rootless and root JVM suites pass, and the
focused native regressions pass. This is **not a complete runtime acceptance**:
Android instrumentation could not run without a device, and lint has an existing
translation backlog. Do not treat this report as certification for unattended
professional audio use or arbitrary untrusted EEL programs.

No feature migration was implemented. The comparison below is a source assessment
for planning; the device acceptance gate remains open.

## Revisions and environment

- Target: `051-lab/RootlessJamesDSP-ViPER4Android-Edition`, branch `main`.
- Starting and ending commit: `8285cad7db9d18079cb4bee2b61cb7af19e02633`.
- `git fetch origin` confirmed zero commits ahead/behind `origin/main`.
- Starting worktree was clean. The fixes, tests and report remain uncommitted.
- Final status: six tracked files modified; new `.gitattributes`, this report,
  and five files under `tests/native/`. No staged changes, commits or pushes.
- Reviewed PR #1 against its first parent using `git diff HEAD^1 HEAD`:
  30 files, 840 insertions, 223 deletions. Its description explicitly said
  validation was not run; its only reported check was CodeRabbit.
- Comparator: [RootlessJamesDSP at 0b5a1cb](https://github.com/051-lab/RootlessJamesDSP/tree/0b5a1cb477860e4f9c1fe21d49d8dae497f360b5).
  Its clean sibling checkout matched GitHub's `main`; native submodule revision
  was `a577e4df2a30828da8c38bb8ada54bdbd894d09c`. It was only read.
- Gradle 8.10.2, Android Gradle Plugin 8.7.3, Kotlin 2.1.0, compile SDK 35,
  target SDK 34, CMake 3.22.1, selected NDK 27.0.12077973.
- Local Gradle ran with Android Studio's JBR 21.0.10. Java/Kotlin output targets
  17; CI specifies JDK 17. The machine's default JDK 25 was not used.
- Native runtime checks used GCC in WSL Ubuntu. Android artifacts used NDK Clang.
  Actual native compile commands contained `-O2`, without `-Ofast`; the top-level
  CMake flag text alone did not describe the effective compiler invocation.

## Build and test results

| Check | Result |
| --- | --- |
| Task discovery, `:app:tasks --all` | Passed; tasks selected from actual output and CI |
| Original rootless JVM suite | 47 tests, zero failures/errors |
| Original root JVM suite | 47 tests, zero failures/errors |
| Final rootless JVM suite | 48 tests, zero failures/errors |
| Final root JVM suite | 48 tests, zero failures/errors |
| Rootless and root Kotlin/Java compilation | Passed through their test/build task dependencies |
| Native C/C++/JNI | Passed for armeabi-v7a, arm64-v8a, x86, x86_64 |
| Rootless F-Droid debug APK | Universal plus four ABI splits built |
| Instrumentation APK | Compiled and packaged |
| APK validation | Universal APK signature verified; all four ABIs contain DSP, JNI wrapper and supporting libraries |
| Native sanitizer checks | Passed; see coverage below |
| Lint | Completed, but not clean: 884 errors, 67 fatal findings, 241 warnings |
| Android instrumentation | Attempt failed with `DeviceException: No connected devices!`; no configured AVD |
| Patch whitespace | `git diff --check` passed |

The initial build took 6m 8s; the build after fixes took 3m 26s. The JVM suites
include CodeRabbit's storage, archive, backup, API/download, file-extension and
slider regressions, plus the pre-existing parser/preset/audio-file tests.

All lint errors are `MissingTranslation`; all fatal findings are
`ExtraTranslation`. The configured `abortOnError = false` means Gradle's successful
exit does not mean lint passed. Warnings include dependency age, unused resources,
locale use, allocations during drawing and accessibility. No translation fixes
or lint suppressions were introduced to manufacture a clean result.

## Defects reproduced and fixed

1. **EEL WAV buffer bounds, high severity.** Both WAV writer helpers accepted a
   two-frame read starting at the last EEL RAM element. `__NSEEL_RAMAlloc` is
   literally pointer addition, so the new null checks did not establish bounds.
   Both helpers now reject offsets outside RAM and frame counts exceeding the
   remaining elements. The regression exercises both actual builtins, invalid
   offsets/NaN/infinity, and a valid last-element write. This was a retained
   weakness in the hardening, not newly introduced pointer arithmetic.
2. **Base64 decoded-string overread, high severity.** AddressSanitizer reproduced
   a four-byte read from a three-byte decoded allocation. The length-taking
   `s_str` constructor copied `length + 1` bytes even though the decoder supplied
   a binary payload without a terminator. Its sole production caller was traced.
   It now copies exactly `length` bytes and appends its own NUL. In-place encode
   and decode pass with ASan/UBSan. This constructor defect predated PR #1.
3. **New uint64 conversion guard, ABI-dependent.** On platforms with 64-bit
   `long double`, converting `UINT64_MAX` to that type rounds up to `2^64`.
   The PR's `>` check therefore accepted an unrepresentable value. The regression
   failed with GCC `-mlong-double-64`; an exact exclusive `>= 0x1p64L` bound fixes it.
   The test models that precision property, not an Android ARM runtime.
4. **Metadata-only backup accepted by extraction.** A TAR containing only
   `is_backup=true` returned valid metadata and could reach clean restore's
   destructive clearing step. The new test failed before the fix. Extraction
   now requires at least one successfully extracted payload entry and cleans up
   on rejection, consistent with the existing `validate()` contract. Valid
   archives and all existing extraction tests continue to pass.
5. **CI excluded this repository's main branch.** The build workflow only
   selected `master`/`viper-extras`. It now includes `main` for pushes and PRs,
   and runs the native regression script. This local workflow edit has not been
   pushed or exercised by GitHub Actions.

The only supporting housekeeping is ignoring Kotlin's generated `.kotlin/`
directory and enforcing LF for the new shell runner. No dependencies, application
features, numerical algorithms or unrelated refactors were added.

## Review of the requested areas

| Area | Assessment and coverage |
| --- | --- |
| `dr_wav.h` | Error propagation for final seek/write/close is appropriate. Host tests cover float WAV round-trip and RIFF/W64 final seek/write failures; no device filesystem fault injection. |
| `eel_matrix.h` | `unsigned int size[2]` matches `geninv`'s output type and does not change buffer size. Arbitrary user-provided dimensions/offsets remain a broader unchecked-input risk; no general matrix safety claim. |
| `nseel-compiler.c` | Delayed destination destruction fixes source/destination alias lifetime. WAV bounds and precision issues above were reproduced and fixed. Arbitrary string handle indices and other EEL native builtins remain outside these focused checks. |
| `FFTConvolver.c` | PR removes an unused expression; no filter computation was changed there. Stereo partitioned convolution, partial calls and IR refresh match a direct reference at 1e-5 tolerance under ASan/UBSan. Refresh callers retain the documented fixed-size contract. |
| `cos_fib_paraunitary.c` | Explicit `int8_t` preserves negative coefficients on unsigned-char targets. Remaining `char` arrays store only lookup indices 0..4. Sparse coefficient construction is tested with both signed- and unsigned-char compilation. Full filter-design spectral equivalence was not measured. |
| `polyphaseFilterbank.c` | The new final `else` lies within `1 <= m < 7`, so it represents the existing m=6 case. Tested positive finite widths for N=1..160 and m=1..8; not a full analysis/synthesis audio sweep. |
| `jdsp_header.h` | Moving the forward declaration before the callback prevents an unintended parameter-scope struct type. All four ABIs compiled. |
| StorageUtils and API downloads | Name validation, stream closure, temporary files, replacement and error cleanup reviewed; related JVM tests pass. Android provider behavior, cancellation timing, disk-full and interrupted replacement need device/fault testing. |
| TAR and BackupManager | Containment, metadata limit, filtering and restore gate reviewed. Empty-payload gap fixed. Restore still is not a transaction if copying fails after current data is cleared. Total archive expansion is not bounded here. |
| UpdateManager and SessionInstaller | Root variant compiles. Installer now returns actual success and unregisters callbacks; manager exposes failure and clears failed cached installs. No real package install/cancel/update exercised. |
| AutoEq | Null response bodies are routed to failure. No live server integration was exercised. |
| MaterialSeekbarPreference | Snapping/range compatibility tests pass; listener reset and restore behavior reviewed. Actual recycled views, non-finite imported controls and touch interactions were not instrumented. |
| FileLibraryPreference | TypedArray recycling and case-insensitive extensions reviewed and extension tests pass. |
| GraphicEqualizerFragment | Superclass state-saving call restored; active editor still deliberately discards edits on save. Rotation/editor behavior remains a device check. |

## LiveProg/EEL capability comparison

These assessments come from the two pinned source trees, not from assuming all
claims in either README or historical test report have been rerun today.

| Capability | RootlessJamesDSP fork | ViPER4Android Edition | Classification |
| --- | --- | --- | --- |
| VM integration | Native NSEEL VM, JNI wrapper | Same engine family, four slot states | A: present, expanded |
| Four lifecycle sections | `@init`, `@slider`, `@block`, `@sample` | Same section model per slot | B: equivalent core contract |
| Execution order | Init then slider at load; slider on update; block once per buffer; sample once per stereo frame | Same native behavior | B |
| Invalid reload | Candidate VM compiled before replacing active VM | Same transactional replacement per slot; JNI keeps prior program on syntax failure | A/B |
| Live parameter refresh | UI broadcasts variable updates for scripts with `@slider`; legacy scripts reload | UI rewrites source and reloads all four slots; native slot setter exists but its JNI export has no matching Kotlin slot declaration/control path | C: worthwhile missing integration |
| Host variables | `srate`, `samplesblock`, `spl0`, `spl1`; sample-rate setter updates srate | Same names, but rate setter does not refresh loaded slots' srate | C: sample-rate parity gap |
| Parameter discovery | Stops at sections, deduplicates names, limit of 128 properties | Similar grammar/property classes but scans all lines without deduplication/limit | C: bounded discovery worth migrating |
| Chaining and order | One LiveProg stage | Four independent VM slots, reorderable among other DSP stages | A/E: stronger host architecture |
| Presets | Single embedded script | v4 embeds liveprog1..4, slot names and DSP preferences; restores gaps, retains legacy v1..3 path | A/E, with caveats below |
| Script reload scope | One stage | Editor/reload broadcast synchronizes all four stages | C/E: target only the affected slot |
| Failure isolation | Compile failure preserves old script; non-finite output becomes zero | Same per-slot behavior | B; neither is a memory/process sandbox |
| Native lifetime | Later wrapper registry/lifetime locking and shutdown safeguards | Simpler raw handles; these protections are not equivalent | C: requires focused integration and stress testing |
| Partial audio buffers | Separates reserved capacity from actual frames | Processing entrypoints refresh convolution when actual n changes | C: stability/performance parity gap |
| CPU/memory | One VM, additional candidate during reload | Four VMs eagerly constructed, plus replacement candidate; synchronous compile/init under DSP mutex | C/E: measure before optimizing |
| Whole native-engine replacement | Native source referenced as submodule | Native source vendored with ViPER effects and chain changes | D: wholesale replacement would discard target-specific functionality |
| Root/remote architecture | External effect backend has different capabilities | Extra LiveProg slot method in remote engine is a no-op returning success | D: do not promise four-slot parity for root backend |

Preset caveats: existing filenames are merged with saved parameters instead of
replaced byte-for-byte; restored occupied slots are explicitly enabled by
`LiveprogSlots.write`. Thus four script payloads are supported, but exact snapshot
restoration of arbitrary existing code and disabled occupied slots is not
established. The existing four-slot instrumentation test was built but not run.
Per-slot independent controls also cannot rely indefinitely on rewriting one
shared script file. Neither host implements `@serialize`/`@gfx` semantics here.

Each compile context contains 2^24 floats of RAM, **64 MiB before other state**.
Four contexts therefore reserve about 256 MiB; a replacement adds another 64 MiB.
This is allocation sizing from source, not measured RSS. `calloc` may initially
use lazy pages, whereas load explicitly clears the RAM array. Reloading every
slot for a single control edit magnifies both zeroing and state reset costs.

Newly useful possibilities include four-stage custom effect presets, selective
reload/parameter changes, and placing EEL stages around native convolution/EQ.
These remain serial audio stages; four slots do not provide parallel execution,
shared VM variables, timeout enforcement or native crash containment.

## Recommended next task

First establish **slot-aware LiveProg lifecycle tests and parameter updates**.
Port the older fork's runtime assertions, adapt them to slots 0..3, and test that
editing one `@slider` parameter preserves all delay/filter state in other slots.
Wire the existing native slot setter through Kotlin and the service with an
explicit slot identity and failure result; retain reload for legacy scripts.
Include srate propagation on 48/44.1 kHz transitions and valid/invalid reload
preservation in the acceptance tests. Bound and deduplicate UI parameter discovery
using the older fork's existing approach.

Before calling that feature ready, run the current EELVault/preset instrumentation
and the ported lifecycle tests on an ARM64 device. The next safety workstream is
native-handle/recorder teardown plus partial-block capacity parity. Do not migrate
the Darwin application's second-engine pipeline or replace the entire native tree.

## Commands and reproducibility

Run from the target repository in PowerShell:

```powershell
git status --short --branch
git fetch origin
git rev-list --left-right --count HEAD...origin/main
git log -5 --oneline
git diff HEAD^1 HEAD --stat
git diff HEAD^1 HEAD -- app/src/main/cpp
git diff HEAD^1 HEAD -- app/src/main/java app/src/root
gh pr view 1 --repo 051-lab/RootlessJamesDSP-ViPER4Android-Edition --json title,body,mergeCommit,statusCheckRollup

$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:tasks --all --console=plain
.\gradlew.bat :app:testRootlessFdroidDebugUnitTest :app:testRootFdroidDebugUnitTest :app:assembleRootlessFdroidDebug :app:lintRootlessFdroidDebug :app:assembleRootlessFdroidDebugAndroidTest --continue --console=plain

# Ran before the TAR fix and failed as expected:
.\gradlew.bat :app:testRootlessFdroidDebugUnitTest --tests '*TarExtractionTest.metadataOnlyBackupCannotReachDestructiveRestore' --console=plain

# Native runner contains the exact GCC/linker commands, including precision mode:
wsl -e sh -lc 'cd /mnt/c/src/051-lab/active/RootlessJamesDSP-ViPER4Android-Edition && sh tests/native/run.sh'

& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
& "$env:ANDROID_HOME\emulator\emulator.exe" -list-avds
.\gradlew.bat :app:connectedRootlessFdroidDebugAndroidTest --console=plain

$apkPath = (Resolve-Path 'app/build/outputs/apk/rootlessFdroid/debug/RootlessViPER4Android-v2.6.0-rootless-fdroid-universal-debug.apk').Path
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --verbose $apkPath
Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath
git diff --check
git status --short --branch
git rev-parse HEAD
```

The full combined Gradle command ran on the original baseline and again after
the fixes. The connected-test task is an attempted device run, not a passing
instrumentation result. Early host harness setup attempts failed because `-I`
selected a bundled Windows dirent header and because ASan retained unused global
function tables. The final runner uses `-iquote`, section garbage collection and
`--param asan-globals=0`; stack/heap ASan and UBSan are enabled, global redzones are
not. The 64-bit long-double variant is a separate non-sanitized host test.

Native red results were: failed WAV boundary assertion, ASan decoded-string
heap overread, and failed uint64 conversion assertion with `-mlong-double-64`.
Final native checks additionally cover direct-convolution equivalence, IR refresh,
signedness, filter-width branches and WAV I/O finalization. They do not run the
entire native audio graph or JNI under sanitizers.

Logs are preserved under `build/verification/`. Gradle HTML/XML reports remain in
`app/build/reports/` and `app/build/test-results/`; APKs are under
`app/build/outputs/apk/rootlessFdroid/debug/`.

Final universal APK SHA-256:
`488e5988651b20b206134afccba12fee13188af08f83386cd037e6c6a61f197c`.
This artifact includes the uncommitted fixes, so the baseline commit SHA alone
does not reproduce it. The attempted connected-test task rebuilt timestamped
BuildConfig data; the hash above was taken afterward and the signature reverified.

## Remaining acceptance risks

- No actual Android test execution, audio listening, Bluetooth/route changes,
  prolonged playback, realtime CPU measurements or measured peak memory.
- Native compilation across four ABIs is not runtime equivalence across them.
  No full FFT/filterbank spectral sweep, fuzzing campaign or allocation-failure
  injection was performed.
- LiveProg raw memory access/string indices and many numerical helpers remain
  unsuitable as a sandbox boundary. A bad native call can affect the process;
  non-finite sample replacement does not prevent memory faults or infinite work.
- Sample-rate propagation, all-slot reloads, native handle lifetime and partial
  audio block refresh differences identified above require runtime regression
  coverage and follow-up before professional deployment.
- Archive expansion limits and transactional restore under failed writes remain
  incomplete. Successful small archive tests do not prove disk-full recovery.
- Updater, storage-provider behavior and fragment/slider UI lifecycles were
  compiled/reviewed, not exercised on an Android system.
- Translation lint debt remains visible. Release/Full/plugin variants, signing
  with private release keys, and root-device integration were not validated.
