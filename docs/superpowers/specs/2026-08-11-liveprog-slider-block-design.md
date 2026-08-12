# Multi-slot LiveProg `@slider` / `@block` Support Design

## Goal

Upgrade every LiveProg engine in RootlessJamesDSP-ViPER4Android-Edition to support the four-stage EEL2 lifecycle already proven in `051-lab/RootlessJamesDSP` / `051-lab/JamesDSPManager`:

- `@init` — execute once after a script compiles successfully.
- `@slider` — execute once after initial load and whenever a host-visible variable in that same LiveProg slot is changed.
- `@block` — execute once for every `LiveProgProcessSlot(jdsp, slot, n)` call with `samplesblock = n`.
- `@sample` — execute once per stereo sample frame.

The ViPER edition's existing four-slot, reorderable DSP-chain architecture must remain intact.

## Current Architecture

The ViPER edition vendors `libjamesdsp` in-tree and owns a custom multi-slot LiveProg layer:

- Slot 0 uses `jdsp->eel`.
- Slots 1–3 use `jdsp->eelExtra[0..2]`.
- `LiveProgProcessSlot(jdsp, slot, n)` is already dispatched independently for each reorderable LiveProg position.
- Each slot currently implements only `codehandleInit` and `codehandleProcess`.
- Source parsing recognizes only `@init` and `@sample` using substring searches.
- Android/JNI EEL variable enumeration and manipulation currently target slot 0 only.

This means the correct solution is a behavioral port of the hardened 051 LiveProg lifecycle into the ViPER edition's slot-aware architecture, not a file replacement.

## Design Principles

1. Preserve all four LiveProg slots and reorderable processing.
2. Preserve compatibility with existing `@init` + `@sample` scripts.
3. Make `@init`, `@slider`, and `@block` optional; keep `@sample` required.
4. Give all four slots identical lifecycle semantics.
5. Compile scripts transactionally so a failed reload never destroys a currently running valid script.
6. Keep synchronization ownership explicit: native LiveProg APIs own locking for script swaps and variable mutations; callers must not compensate with stray unlocks.
7. Avoid unrelated DSP/UI refactoring.

## Native LiveProg State

Extend `LiveProg` in `jdsp_header.h` from two execution handles to four:

```c
NSEEL_CODEHANDLE codehandleInit;
NSEEL_CODEHANDLE codehandleSlider;
NSEEL_CODEHANDLE codehandleBlock;
NSEEL_CODEHANDLE codehandleProcess;

float *vmFs;
float *samplesBlock;
float *input1;
float *input2;
```

Every slot registers:

- `srate`
- `samplesblock`
- `spl0`
- `spl1`

## Section Parsing

Replace the current `strstr("@init")` / `strstr("@sample")` parser with a line-aware section splitter modeled on the hardened 051 implementation.

Recognized host sections:

- `@init`
- `@slider`
- `@block`
- `@sample`

Requirements:

- Section directives are recognized only as section lines, not arbitrary substrings inside DSP expressions or comments.
- Duplicate recognized sections are rejected.
- Unknown `@...` sections do not become executable LiveProg sections.
- `@sample` is mandatory.
- `@init`, `@slider`, and `@block` may be absent or empty.
- `@sample`-only scripts are accepted, matching the hardened 051 reference implementation.
- Each section is compiled independently so syntax failures identify the lifecycle stage that failed.

Error codes retain existing meanings where possible and add dedicated errors for slider, block, duplicate section, and allocation/VM setup failure.

## Transactional Script Loading

Script loading must compile into a candidate `LiveProg` state for the selected slot.

Flow:

1. Split source into lifecycle sections.
2. Allocate and initialize a candidate VM.
3. Register host variables.
4. Compile present sections independently.
5. Execute `@init`, then `@slider`, on the candidate.
6. If every required step succeeds, atomically swap the candidate into the requested slot while holding the DSP mutex.
7. Destroy the previous slot state after the swap.
8. If compilation fails, destroy only the candidate and leave the currently running slot untouched.

This behavior must apply to slots 0–3.

## Runtime Lifecycle

`LiveProgProcessSlot(jdsp, slot, n)` becomes the single block-entry path for every slot.

For each enabled, compiled, active slot:

```text
samplesblock = n
execute @block once, if present
for i in 0 .. n-1:
    spl0/spl1 = current frame
    execute @sample
    sanitize non-finite output
    write frame back
```

`@block` executes once per LiveProg process invocation, not once per sample and not according to an assumed Android callback size independent of `n`.

## Slot-aware `@slider`

Add native APIs:

```c
int LiveProgSetVariableSlot(JamesDSPLib *jdsp, int slot,
                            const char *name, float value);
int LiveProgSetVariable(JamesDSPLib *jdsp,
                        const char *name, float value);
```

The non-slot API is a backwards-compatible slot-0 wrapper.

`LiveProgSetVariableSlot` must:

1. Validate slot, name, and finite value.
2. Lock the DSP.
3. Resolve the selected slot VM.
4. Resolve the named EEL variable.
5. Write the new value.
6. Execute that same slot's `@slider` handle if present.
7. Unlock and return success/failure.

Direct JNI mutation of NSEEL's internal variable table must no longer be the primary parameter-write path.

## Android / JNI Slot APIs

Add local-engine slot-aware VM access while preserving the existing slot-0 API surface:

```kotlin
enumerateEelVariablesSlot(self, slot)
manipulateEelVariableSlot(self, slot, name, value)
```

Existing APIs remain aliases for slot 0:

```kotlin
enumerateEelVariables(self)
manipulateEelVariable(self, name, value)
```

`JamesDspBaseEngine` and `JamesDspLocalEngine` expose matching slot-aware methods. Existing callers continue to work unchanged.

`JamesDspRemoteEngine` currently reports `supportsEelVmAccess() == false` and its EEL VM utilities are intentionally unavailable. This feature will preserve that contract: remote-engine slot-aware enumeration/manipulation returns empty/false and does not add a new remote transport protocol. Slot-aware host-variable control is therefore a local/rootless-engine capability in this change.

## Locking

The current JNI "workaround due to library bug" that performs a manual `jdsp_unlock(dsp)` after parser calls must be removed as part of the parser migration if the new parser owns its locking correctly.

No path may double-unlock or depend on unmatched lock state.

Processing dispatch may continue to guard LiveProg processing with the existing DSP mutex if that remains the repository's established controller contract. Parser/variable mutation functions must be reviewed against that controller contract to avoid recursive lock acquisition.

## Compatibility

The following must remain valid:

```eel
@init
// initialization

@sample
// processing
```

A minimal script containing only `@sample` must also remain valid:

```eel
@sample
spl0 *= 0.5;
spl1 *= 0.5;
```

New full lifecycle scripts must load in all four slots:

```eel
@init
// one-time setup

@slider
// parameter-derived state

@block
// block-rate state

@sample
// audio-rate DSP
```

## Verification Strategy

### Parser tests

Verify:

- legacy `@init` + `@sample`
- `@sample` only
- all four sections
- optional empty sections
- alternate valid ordering handled according to the section splitter contract
- duplicate recognized sections rejected
- missing `@sample` rejected
- syntax errors reported separately for init/slider/block/sample
- section marker text inside comments/expressions does not split source incorrectly

### Runtime lifecycle test script

Use an instrumented script containing counters/state:

```eel
@init
init_count += 1;

@slider
slider_count += 1;

@block
block_count += 1;
seen_block_size = samplesblock;

@sample
sample_count += 1;
spl0 *= gain;
spl1 *= gain;
```

Verify for every slot:

- `init_count` increments once per successful load.
- `slider_count` increments once per successful load.
- a host variable write increments `slider_count` exactly once in the targeted slot only.
- `block_count` increments once per `LiveProgProcessSlot` call.
- `seen_block_size == n`.
- `sample_count` increments exactly `n` times per process call.

### Hot-reload safety

1. Load valid script A and enable it.
2. Attempt to load invalid script B.
3. Confirm B reports its error.
4. Confirm A remains compiled, active, and processing.

### Multi-slot isolation

Load four different scripts simultaneously and verify:

- each VM keeps independent variables/state;
- slider writes affect only the targeted slot;
- block/sample counters advance independently;
- reordering the DSP chain does not change lifecycle semantics.

### Regression checks

- Existing LiveProg enable/disable behavior.
- Preserve existing slot-0 freeze behavior exactly. Multi-slot freeze controls are out of scope because the current application exposes no extra-slot VM freeze UI/API.
- Existing reorderable chain.
- Non-finite output sanitation.
- Script error callbacks.
- Build succeeds for the repository's supported Android variants.

## Files Expected in Scope

Primary:

- `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/liveprogWrapper.c`
- `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdsp_header.h`
- `app/src/main/cpp/libjamesdsp-wrapper/JamesDspWrapper.cpp`
- `app/src/main/java/me/timschneeberger/rootlessjamesdsp/interop/JamesDspWrapper.kt`
- `app/src/main/java/me/timschneeberger/rootlessjamesdsp/interop/JamesDspBaseEngine.kt`
- `app/src/main/java/me/timschneeberger/rootlessjamesdsp/interop/JamesDspLocalEngine.kt`

Review/conditional:

- `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdspController.c`
- `app/src/main/java/me/timschneeberger/rootlessjamesdsp/interop/JamesDspRemoteEngine.kt`
- any tests/build wiring needed to exercise the native lifecycle.

## Out of Scope

- Changing ViPER DSP algorithms.
- Reworking the processing-order UI.
- General JSFX compatibility beyond `@init`, `@slider`, `@block`, `@sample` and `samplesblock`.
- New LiveProg UI controls unrelated to making existing variable interaction slot-aware.
- Multi-slot freeze control.
- New remote-engine EEL VM transport.
- Refactoring unrelated native effects.

## Success Criteria

The feature is complete when:

1. Any of the four LiveProg slots can load legacy scripts unchanged.
2. Any of the four slots can compile and execute `@init`, `@slider`, `@block`, and `@sample` with the lifecycle defined above.
3. `samplesblock` is correct for every slot.
4. Local-engine host parameter changes execute `@slider` only for the targeted slot.
5. Failed script reloads preserve the prior valid script.
6. No manual unmatched mutex workaround remains in the migrated LiveProg path.
7. Existing reorderable-chain behavior remains intact.
8. Automated/native verification and Android build checks pass before merge.