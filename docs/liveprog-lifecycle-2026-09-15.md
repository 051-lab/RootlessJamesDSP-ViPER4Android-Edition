# Baseline closure and LiveProg lifecycle investigation

## Scope and acceptance

Branch: `dev/verified-baseline-2026-09-15`, based on
`8285cad7db9d18079cb4bee2b61cb7af19e02633`. Nothing is pushed.
The earlier `verified-baseline-2026-09-15.md` is a historical snapshot of the
initial audit, not the current Git status. Its four fixes are preserved in:

- `2da4862`: EEL WAV bounds, uint64 conversion and decoded-string ownership,
  with native regressions and LF handling for the runner.
- `b892851`: reject metadata-only archive extraction, with the failing-case test.
- `980f7b1`: initial report, main-branch CI selection, native checks, Kotlin cache ignore.
- `a45945e`: lifecycle/parser/JNI test foundation and the two VM invariant fixes.

No production parameter routing, parser migration, translation cleanup or lint
baseline was implemented. VM pointer-table alignment and floating-point stack
sentinel fixes were needed during the lifecycle investigation. **The expanded native acceptance gate
is not green:** strict UBSan exposes inherited interpreter/filterbank alignment
defects beyond the original focused regression suite. Do not equate successful
Android compilation with a safe runtime certification.

## Review of the baseline patch

Every changed/new baseline file was reviewed. Production edits are limited to
reproduced faults; there is no DSP algorithm replacement or feature port.

- Both WAV builtins reject out-of-range offsets before subtracting from RAM
  capacity. The remaining-frame comparison avoids unsigned underflow. The
  interleaved allocation has a checked channel/frame/element-size product.
  Error branches release temporary buffers, not VM-owned RAM. Tests call both
  actual builtins, exercise the final valid RAM element, and reject overflow.
- The decoded-string constructor copies exactly the payload length and owns its
  appended terminator. Its sole production caller is Base64 decode. In-place
  decoding exercises source/destination aliasing and the three-byte allocation.
- The uint64 exclusive `2^64` bound is exact. The old issue occurs with
  **insufficient significand precision**, such as 64-bit-storage `long double`,
  not because extra precision is available. The separate precision-mode test
  models that conversion without claiming Android runtime coverage.
- TAR counts successfully extracted non-metadata payload entries. Rejection
  cleans temporary extraction and returns failure before BackupManager's
  destructive restore gate. The regression contains valid backup metadata but
  no payload, rather than merely an invalid TAR header.
- CI changes only include `main` and the focused native runner. `.kotlin/` and
  shell line endings are housekeeping. Build logs/APKs/native binaries are ignored.

## Additional native findings

1. **Fixed: VM variable-name table alignment.** `llBlock.block` starts after a
   pointer and an int; its address need not satisfy pointer alignment. Allocating
   `char **` with alignment 1 caused a UBSan misaligned pointer load even during
   host-variable registration. `newCtxDataBlock` already supports alignment;
   requesting `_Alignof(char *)` is the minimal fix. Allocation size, ownership,
   free path, variable names and DSP math are unchanged. It is an inherited
   allocator-caller defect, not introduced by PR #1. The new allocation-only
   probe creates/enumerates all four real VMs with strict UBSan.
2. **Fixed: floating-point stack's empty sentinel.** The interpreter formed
   `fpstack - 1` before its array; UBSan's object-size check then stopped at the
   first push. Reserve one sentinel element and start at that element instead.
   Pre-increment push/post-decrement pop semantics and the 64-value usable
   capacity stay unchanged. The real compiled lifecycle scripts reproduce the
   failure before this two-line change; no bytecode/opcode layout changes.
3. **Open: portable EEL bytecode alignment.** `glue_port.h` writes pointer-sized
   operands immediately after four-byte opcodes using typed pointer casts
   (first reproduced at line 150). Interpreter reads use the same packed layout.
   Eight-byte pointer alignment is not guaranteed on 64-bit hosts. This needs a
   paired encode/decode review, preferably unaligned-safe `memcpy` access without
   changing bytecode layout, followed by opcode and ABI coverage. Fixing one
   observed cast would leave sibling operations broken. No broad rewrite here.
4. **Open: stereo-enhancement filterbank storage alignment.** Full
   `JamesDSPInit` reaches `StereoEnhancementRefresh` and casts a `char subband`
   array in `stereoEnhancement` to `WarpedPFB *`. The containing type does not
   guarantee the required alignment; strict UBSan fails in `initWarpedPFB`.
   Review both base alignment and per-channel stride before fixing it; also audit
   the separate EEL RAM-backed filterbank builtins. No filterbank layout change
   was bundled into this lifecycle task.
5. **Open: formatter alignment.** Recovery-mode failed-compilation tests also
   report unaligned word access in vendored `stb_sprintf.h` while formatting
   compiler errors. Its existing `STB_SPRINTF_NOUNALIGNED` option is a candidate
   for a focused follow-up; this is not a newly introduced PR change.

The host lifecycle harness constructs only LiveProg and its IO/lock state, but
links the real core source set and calls the real chain dispatcher and sample-rate
setter. `--full-engine-init` keeps the full initialization fault reproducible.
Default builds stop on all UBSan failures. The explicit
`LIVEPROG_ALIGNMENT_RECOVERY=ON` diagnostic build reports alignment failures and
continues to behavioral assertions; ASan and all other UBSan failures remain fatal.
Recovery is not a passing sanitizer result and is not installed in CI.

## Lint provenance

Three source states were checked with the same JBR/Gradle/AGP setup:

| Source | Error | Fatal | Warning |
| --- | ---: | ---: | ---: |
| Pre-PR first parent `4a0d5525a4814e26ba3541c2659a79d71fc5dbdb` | 891 | 67 | 246 |
| Raw merge `8285cad` | 884 | 67 | 240 |
| Committed baseline fixes | 884 | 67 | 241 |

All 884 `MissingTranslation` and 67 `ExtraTranslation` findings predate PR #1.
The PR removed six `StringFormatInvalid` errors, one
`StartActivityAndCollapseDeprecated` error, two `StringFormatCount` warnings,
two `Recycle` warnings and one `DuplicateIncludedIds` warning. No new
source-related finding was found. The remaining warning-count variation is the
external `GradleDependency` advisory for `kotlinx-serialization-json` 1.5.0;
that dependency declaration was unchanged. Issue severity/ID/message multisets
were compared, ignoring line-number movement and checkout paths.

Historical sources were exported with `git archive` to
`C:\src\rjdsp-lint-20260915`, not checked out over the development branch. Gradle
requires Git metadata for BuildConfig, so these diagnostic exports used the main
repository's `GIT_DIR` and their own `GIT_WORK_TREE`. Their generated commit/count
fields therefore are not historical artifact provenance; the linted source is
the exact exported revision. XML snapshots and logs are in `build/verification`.

Recommendation, **not implemented**: review and check in an AGP lint baseline
for the supported variants, then make the dedicated CI lint job fail on new
errors/fatal findings (`abortOnError=true` for that gate). Pin tool versions,
archive full reports, prohibit automatic baseline regeneration in ordinary CI,
and remove resolved entries through reviewed updates. Decide separately whether
new warnings should fail; do not silently promote all existing warning types.
A baseline alone would not fail CI while the current `abortOnError=false` remains.
This follows Android's [lint baseline guidance](https://developer.android.com/studio/write/lint#snapshot).

## Four-slot behavioral contract

| Operation | Current behavior |
| --- | --- |
| Construction | Four independent VM contexts, including empty slots; each registers `srate`, `samplesblock`, `spl0`, `spl1`. |
| Successful script load | Split sections; compile a candidate; execute `@init`, then `@slider`; swap only the target; free the old VM; preserve its `active` flag. |
| Invalid replacement | Discard candidate; retain previous program/state and enablement. Error codes: init -1, missing sample -2, sample -3, slider -4, block -5, duplicate section -6, allocation/invalid target -7. |
| Native parameter update | Find an existing variable in slot N, set a finite value, execute its `@slider`; no compile, init, or other-slot reset. Invalid slot/name/unknown variable returns false. |
| Audio buffer | `samplesblock=n`; `@block` once per processed buffer; `@sample` once per stereo frame. Non-finite output samples become zero. |
| Empty/missing source through JNI/engine | Disable that slot. This is different from a malformed replacement: disabling can retain its compiled VM allocation. |
| Rate change | Host engine fs changes, but already-loaded VM `srate` stays stale. Re-enabling refreshes `srate` without init/slider; reloading runs init/slider. |
| Reordering | Reorders fixed effect IDs 13..16, mapping to slots 0..3. It does not relocate VM ownership. |

`@init` is optional; `@sample` is required. Unknown sections do not imply JSFX
`@gfx`/`@serialize` support. Empty optional sections do not compile a handle.
Metadata declarations above sections do not automatically initialize native
variables: the UI parser's persisted assignments and EEL code are distinct.

Rate path: `RootlessAudioProcessorService` determines/clamps 44.1–48 kHz,
sets `JamesDspLocalEngine.sampleRate`, calls JNI `setSamplingRate`, then
`JamesDSPSetSampleRate`. The native setter can select an internal ASRC rate for
other rates; LiveProg should receive processing fs, not blindly hardware fs.
The older fork updates `eel.vmFs` in its native setter without rerunning init or
slider. This application has not extended that update to four slots.

Lifecycle tests use persistent scalar counters and EEL RAM cells. They check
all four target slots, 8-frame then 3-frame buffers, selected reloads, all six
section failures, optional sections, empty middle slots, noncommutative chain
order, and stale rate values in both rate directions. Negative probes separately
require correct rate propagation and simulate the current broad-reload effect.
Android tests exercise real JNI reload/failure/empty replacement, gaps/reordering,
and per-slot stale rate behavior; they are **built, not device-executed**.

## Parameter discovery and reference comparison

Reference source: sibling `RootlessJamesDSP` at
`0b5a1cb477860e4f9c1fe21d49d8dae497f360b5`, native revision
`a577e4df2a30828da8c38bb8ada54bdbd894d09c`; read only.

Both parsers use `EelPropertyFactory` and the numeric/list grammar. The reference
scans with `lineSequence`, stops at the first section, deduplicates keys and caps
properties at 128. This parser scans every line, has no cap and keeps duplicates,
including metadata-like comments inside code sections. Its finite/range checks
reject malformed numeric ranges without aborting later valid discovery.

New JVM tests characterize 127/128/129/256 properties, duplicate/post-section
metadata, malformed/overflow/reversed/zero-step ranges and four independent
parser instances. They intentionally do not claim the desired bound exists.
Each fragment has its own parser/property objects, and native variables are
per-VM. However, duplicate keys collide within a preference screen; assigning the
same file to multiple slots can share persisted parameter values through that
file. `LiveprogSlots.toggle` normally prevents duplicate selection, but this is
not a general per-slot persistence guarantee for imported/manually edited state.

The reference's init/slider/block/sample and failed-reload contracts are largely
equivalent. Its slot-0 live-update path, bounded discovery and rate propagation
are useful targeted references. Four-slot chaining, stable slot identity and v4
preset payloads are stronger here. Do not replace the vendored native tree or
port the old app's entire service architecture. Root/remote extra-slot methods
currently return success without implementation; four-slot parity cannot be
promised there.

Each VM embeds 2^24 floats (64 MiB); four eagerly allocated VMs imply about
256 MiB before other state, plus a candidate during reload. This is allocation
sizing, not measured RSS. Compilation, init and slider execute under the DSP
mutex. Long/infinite EEL work can stall audio; a native fault is not isolated by
slot. No CPU/latency/power or prolonged-device-memory claim is made.

## Current call path and exact identity loss

1. `PreferenceGroupFragment` knows the slot from its XML resource/key triple.
   Its parameter-button click sends only `EXTRA_TARGET_FILE` to
   `LiveprogParamsActivity`: **this is the first loss of explicit slot identity**.
2. Activity creates `LiveprogParamsFragment.newInstance(targetFile)`; saved state
   also preserves only the path. Numeric/list callbacks change an `EelProperty`.
3. `EelParser.manipulateProperty` rewrites an assignment in the source file.
   Numeric/list/default-reset paths emit bare `ACTION_SERVICE_RELOAD_LIVEPROG`.
4. `RootlessAudioProcessorService` handles that action by syncing all four
   preference namespaces. There is no selected-slot or variable payload.
5. `JamesDspBaseEngine.syncWithPreferences` maps those namespaces to slot-0
   `setLiveprog` and extra-slot `setLiveprogSlot(1..3)`, reads each source file,
   and calls the corresponding `JamesDspLocalEngine` wrapper method.
6. Kotlin `JamesDspWrapper` calls JNI; native
   `LiveProgStringParserSlot` compiles/replaces each selected VM. Other slots'
   filter/delay/envelope/oscillator state is lost even when their files did not change.

The existing slot-aware JNI `manipulateEelVariableSlot` and
`enumerateEelVariablesSlot` exports have no corresponding Kotlin declarations.
Existing Kotlin `manipulateEelVariable` reaches slot 0 only and is not the path
used by this parameter screen. No view model carries slot identity in this flow.

## Proposed implementation, not applied

Preserve `(slot, script identity, variable, value)` explicitly:

`PreferenceGroupFragment -> ParamsActivity/Fragment -> local parameter-update
action -> service -> Base/LocalEngine -> Kotlin slot JNI declaration -> existing
JNI manipulateEelVariableSlot -> LiveProgSetVariableSlot -> VM N @slider`.

- Pass zero-based slot 0..3 from the known preference/XML mapping; retain it in
  intent, fragment arguments and saved state. Never derive it from chain position.
- Give parameter updates their own action with slot, canonical script path,
  parameter name and finite value. Reject missing/out-of-range slots; never default
  an invalid command to slot 0. Check source persistence success before dispatch.
- Validate that the requested slot still owns the expected successfully loaded
  script. For same-path replacement races, use a per-slot accepted-source revision
  (hash) or generation plus an acknowledged update result; path alone is not a
  sufficient identity. Advance accepted revision only after a successful load or
  accepted parameter edit, not after failed compilation. Reject stale commands
  and refresh UI metadata rather than targeting the replacement VM.
- Keep native-handle access on the service/control owner, serialized with close.
  Do not send JNI calls directly from fragment callbacks or detached coroutines.
- Modern scripts with a real `@slider` section use the native variable setter.
  Legacy scripts whose derived values are calculated in `@init` need a **targeted
  reload of N**, not the broad reload action. A rejected unknown variable is an
  observable failure, not permission to reload every slot.
- Keep the broad action for explicitly global preset/file operations until their
  scope is designed separately. Default reset must either batch values with a
  defined slider policy or reload only N; do not let a sequence of edits reset peers.
- Initially reject ambiguous shared-file live updates or require a distinct copy.
  Truly independent persisted values for the same file need a separate per-slot
  parameter store/preset schema design; do not silently solve that by editing all VMs.

Operation boundaries:

| Operation | Required effect |
| --- | --- |
| Parameter value | Set N's existing variable; run N's slider; persist deliberately. |
| Metadata refresh | Reparse UI descriptions/ranges only; no VM reset. |
| Script reload | Compile candidate for N; successful replacement resets N only. |
| Script-file replacement | Validate new script identity; preserve old VM on failure; invalidate stale UI commands. |
| Sample-rate update | Update all populated VMs' processing-rate host variable; decide coefficient refresh policy explicitly; no blanket recompile. |

Expected production files: `PreferenceGroupFragment.kt`,
`LiveprogParamsActivity.kt`, `LiveprogParamsFragment.kt`, `Constants.kt`,
`RootlessAudioProcessorService.kt`, `JamesDspBaseEngine.kt`,
`JamesDspLocalEngine.kt`, `JamesDspRemoteEngine.kt`, `JamesDspWrapper.kt`.
Reuse the existing JNI setter; no native parameter-routing change is necessary.
`EelParser.kt` is a separate bounded-discovery task, not a prerequisite for a
large parser rewrite. `LiveprogEditorActivity.kt`/`Preset.kt` need changes only
when their broader reload operations are deliberately made selective.

Before implementing routing, extend service/JNI tests to assert the selected
command preserves peer counters and rejects stale script identity. Existing
native tests prove the lower-level setter behavior, not the future broadcast
contract. Compatibility risks include legacy init-only effects, shared files,
failed persistence/compile, stale UI after replacement/preset, root backend
limitations, teardown races and long-running slider code.

## Next step

First resolve the now-reproduced native alignment acceptance failures with
focused tests and four-ABI validation. Then implement the selected-slot path
above, retaining these persistent-state tests. Separately migrate bounded
discovery and fix four-slot sample-rate propagation; do not combine them into an
opaque feature port. Run the full instrumentation suite on the Samsung Galaxy
S24 Ultra before declaring the platform ready for professional effects.

## Verification commands and evidence

Final results from the committed code:

| Check | Result |
| --- | --- |
| Rootless F-Droid JVM | 52 tests; 0 failures, 0 errors |
| Root F-Droid JVM | 52 tests; 0 failures, 0 errors |
| Kotlin/Java, debug APK, instrumentation APK | Passed; combined Gradle run 1m 52s |
| Android native/JNI | Build tasks completed for arm64-v8a, armeabi-v7a, x86, x86_64; DSP and wrapper libraries verified inside universal APK |
| Original focused native suite | All six runs passed; five ASan/UBSan runs plus the long-double precision variant |
| New VM allocation-only probe | Strict ASan/UBSan passed |
| New lifecycle behavioral assertions | Passed in explicit alignment-recovery mode; not sanitizer-clean |
| Broad-reload negative control | Failed the persistent-state assertion, as required |
| Required sample-rate propagation | Failed: all four loaded VMs stayed stale |
| Strict expanded native acceptance | Failed on bytecode alignment; separate full-init probe failed on filterbank alignment |
| APK signature | Valid APK Signature Scheme v2, one signer |
| Final lint | 884 errors, 67 fatal, 240 warnings; exact severity/ID/message multiset matches raw merge |
| Real Android device tests | Not run: connected-test task failed with `DeviceException: No connected devices!`; no AVD configured |

The final 240-warning count has no source-related difference from the earlier
241-warning baseline: the external dependency-update advisory was absent again.
No lint configuration was weakened. New native lifecycle checks are opt-in while
the strict acceptance failures remain open; the existing CI still runs the
original focused native suite and automatically discovers the new JVM tests.

Universal debug APK SHA-256:
`6fe011cb3238102f4f1175891affb7cf6c623ac2072d6e2d744d65ab630c2121`.
APK: `app/build/outputs/apk/rootlessFdroid/debug/RootlessViPER4Android-v2.6.0-rootless-fdroid-universal-debug.apk`.
Instrumentation APK compilation is not substituted for execution. Primary device
acceptance target remains the Samsung Galaxy S24 Ultra, including prolonged
stateful playback, route/rate changes, preset restoration and teardown stress.

Environment remains JBR 21.0.10, Gradle 8.10.2, AGP 8.7.3, NDK
27.0.12077973 and WSL Ubuntu GCC. CI/JDK 17 was not executed remotely.
The final code revision used for verification is
`a45945ed3e95c42035d796a33fdbffdb3e9498e0`; the subsequent documentation commit
does not change code or tests.

PowerShell, from the repository root:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
# Pre-commit repeat; repeated after parser tests were added:
.\gradlew.bat :app:testRootlessFdroidDebugUnitTest :app:testRootFdroidDebugUnitTest --console=plain

# After the baseline commits and again after the lifecycle/fix commit:
.\gradlew.bat :app:testRootlessFdroidDebugUnitTest :app:testRootFdroidDebugUnitTest :app:assembleRootlessFdroidDebug :app:lintRootlessFdroidDebug :app:assembleRootlessFdroidDebugAndroidTest --continue --console=plain

# Also built the new instrumentation tests before committing:
.\gradlew.bat :app:assembleRootlessFdroidDebug :app:assembleRootlessFdroidDebugAndroidTest --console=plain

# Attempted, not passed:
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
& "$env:ANDROID_HOME\emulator\emulator.exe" -list-avds
.\gradlew.bat :app:connectedRootlessFdroidDebugAndroidTest --console=plain

# Run before commits, after the baseline commits, and after the lifecycle commit:
wsl -e sh -lc 'cd /mnt/c/src/051-lab/active/RootlessJamesDSP-ViPER4Android-Edition && sh tests/native/run.sh'

# Initial strict lifecycle build: reproduced a VM pointer-table alignment error.
# After fixing that, full init exposed filterbank alignment; LiveProg-only
# initialization exposed bytecode alignment. No failure is counted as a pass.
wsl -e sh -lc 'cd /mnt/c/src/051-lab/active/RootlessJamesDSP-ViPER4Android-Edition && sh tests/native/run-liveprog.sh'

# Diagnostic mode: alignment reports stay visible. First stopped at the FP stack
# object-size error; after its sentinel fix the behavioral assertions passed.
wsl -e sh -lc 'cd /mnt/c/src/051-lab/active/RootlessJamesDSP-ViPER4Android-Edition && sh tests/native/run-liveprog.sh -DLIVEPROG_ALIGNMENT_RECOVERY=ON'
wsl -e sh -lc 'cd /mnt/c/src/051-lab/active/RootlessJamesDSP-ViPER4Android-Edition && cmake --build build/native-liveprog --parallel 2 && build/native-liveprog/liveprog_lifecycle'

$native='/mnt/c/src/051-lab/active/RootlessJamesDSP-ViPER4Android-Edition/build/native-liveprog/liveprog_lifecycle'
wsl -e env UBSAN_OPTIONS=halt_on_error=1 $native --vm-allocation-only
wsl -e env UBSAN_OPTIONS=halt_on_error=1 $native
wsl -e env UBSAN_OPTIONS=halt_on_error=1 $native --full-engine-init
wsl -e env UBSAN_OPTIONS=halt_on_error=0 $native --require-rate-propagation
wsl -e env UBSAN_OPTIONS=halt_on_error=0 $native --simulate-broad-reload
wsl -e env UBSAN_OPTIONS=halt_on_error=0 $native
```

Expected probe exits: allocation-only 0; strict bytecode 1; strict full init 1;
required rate propagation 1 (all four stale); simulated broad reload 134
(persistent-state assertion); recovery-mode characterization 0 **with alignment
diagnostics**. Original six focused native runs have no sanitizer findings;
their existing runner disables global ASan redzones for linker garbage collection.
The full-core lifecycle harness does not disable global redzones.

Lint snapshots used these source exports (one generated diagnostic directory,
outside the development checkout; retained for inspection):

```powershell
New-Item -ItemType Directory -Path C:\src\rjdsp-lint-20260915
git archive --format=tar --output=build/verification/lint-parent.tar 4a0d5525a4814e26ba3541c2659a79d71fc5dbdb
tar -xf build/verification/lint-parent.tar -C C:\src\rjdsp-lint-20260915
# A first attempt without Git metadata failed during BuildConfig configuration.
# In the diagnostic process only:
$env:GIT_DIR='C:\src\051-lab\active\RootlessJamesDSP-ViPER4Android-Edition\.git'
$env:GIT_WORK_TREE='C:\src\rjdsp-lint-20260915'
& C:\src\rjdsp-lint-20260915\gradlew.bat -p C:\src\rjdsp-lint-20260915 :app:lintRootlessFdroidDebug --console=plain
# Save its XML, then repeat with the raw merge source:
git archive --format=tar --output=build/verification/lint-merge.tar 8285cad
tar -xf build/verification/lint-merge.tar -C C:\src\rjdsp-lint-20260915
& C:\src\rjdsp-lint-20260915\gradlew.bat -p C:\src\rjdsp-lint-20260915 :app:lintRootlessFdroidDebug --console=plain
```

Those `GIT_*` overrides were confined to diagnostic child processes, not the
development shell. Lint XML copies are `lint-parent.xml`, `lint-merge.xml`,
`lint-baseline.xml` under `build/verification`.

Final artifact and worktree checks:

```powershell
$apkPath=(Resolve-Path 'app/build/outputs/apk/rootlessFdroid/debug/RootlessViPER4Android-v2.6.0-rootless-fdroid-universal-debug.apk').Path
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --verbose $apkPath
Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath
git diff --check
git status --short --branch
git rev-parse HEAD
```

Logs: `closure-precommit-jvm.log`, `closure-native.log`,
`closure-postcommit.log`, `closure-postcommit-native.log`, `lifecycle-jvm.log`,
`lifecycle-build.log`, `closure-device.log`, `closure-final.log`,
`closure-final-native.log`, `closure-final-lifecycle.log`, `lint-parent.log`,
`lint-merge.log`; native investigation logs use `liveprog-*.log`.
Some early host builds used `/tmp`, which did not persist between later WSL
invocations; the committed lifecycle runner uses ignored `build/native-liveprog`.
