# Multi-slot LiveProg `@slider` / `@block` Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade all four local LiveProg slots so EEL2 scripts support `@init`, `@slider`, `@block`, and `@sample`, with correct per-slot parameter dispatch, `samplesblock`, transactional reload safety, and no regression to the ViPER edition's reorderable DSP chain.

**Architecture:** Keep the ViPER edition's existing slot model (`jdsp->eel` plus `jdsp->eelExtra[0..2]`) and make the hardened 051 LiveProg lifecycle slot-generic. Native code owns parsing, VM lifecycle, script swapping, block/sample execution, and variable mutation; JNI/Kotlin expose slot-aware access while preserving slot-0 compatibility. Android instrumentation tests exercise the real native engine rather than a mock parser.

**Tech Stack:** C11/NSEEL (`libjamesdsp`), C++17/JNI, Kotlin/JVM 17, Android NDK/CMake 3.22.1, JUnit 4 Android instrumentation, Gradle Android variants.

## Global Constraints

- Preserve all four LiveProg slots and reorderable processing.
- Preserve compatibility with existing `@init` + `@sample` scripts.
- Make `@init`, `@slider`, and `@block` optional; keep `@sample` required.
- Give slots 0–3 identical lifecycle semantics.
- Failed reloads must leave the previously running valid script intact.
- Native LiveProg APIs own locking for script swaps and variable mutations; remove unmatched/manual JNI unlock workarounds from the migrated path.
- `@block` executes once per `LiveProgProcessSlot(jdsp, slot, n)` invocation with `samplesblock = n`.
- `@slider` executes once after successful load and exactly once after a host-visible variable write in the targeted slot.
- Remote-engine EEL VM access remains unsupported; do not invent remote slot transport.
- Slot-aware freeze controls are out of scope.
- Do not change ViPER DSP algorithms, processing-order UI behavior, or unrelated native effects.
- Use `rootlessFdroid` variants for deterministic local/CI verification unless a task explicitly requires another flavor.

---

## File Structure

**Native lifecycle**
- Modify `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/liveprogWrapper.c` — slot lookup, lifecycle state, source splitting, candidate VM compilation/swap, variable writes, block/sample execution.
- Modify `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdsp_header.h` — `LiveProg` state and public slot-aware LiveProg APIs.
- Review only `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdspController.c` — verify the existing `n`-based dispatch remains correct; change only if tests prove a lifecycle defect.

**JNI / Kotlin host bridge**
- Modify `app/src/main/cpp/libjamesdsp-wrapper/JamesDspWrapper.cpp` — slot-aware enumeration/manipulation, modern parser error buffer, remove parser unlock workaround.
- Modify `app/src/main/java/me/timschneeberger/rootlessjamesdsp/interop/JamesDspWrapper.kt` — slot-aware native declarations while preserving slot-0 methods.
- Modify `app/src/main/java/me/timschneeberger/rootlessjamesdsp/interop/JamesDspBaseEngine.kt` — slot-aware EEL VM abstract API.
- Modify `app/src/main/java/me/timschneeberger/rootlessjamesdsp/interop/JamesDspLocalEngine.kt` — local implementation using JNI.
- Modify `app/src/main/java/me/timschneeberger/rootlessjamesdsp/interop/JamesDspRemoteEngine.kt` — explicit unsupported slot methods returning empty/false, matching existing remote EEL behavior.

**Verification**
- Create `app/src/androidTest/java/me/timschneeberger/rootlessjamesdsp/interop/LiveProgLifecycleInstrumentedTest.kt` — real JNI/native lifecycle tests.
- No production dependency additions are required; Android instrumentation already has JUnit and AndroidX test dependencies.

---

### Task 1: Establish slot-aware VM access without changing script parsing

**Files:**
- Modify: `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdsp_header.h`
- Modify: `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/liveprogWrapper.c`
- Modify: `app/src/main/cpp/libjamesdsp-wrapper/JamesDspWrapper.cpp`
- Modify: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/interop/JamesDspWrapper.kt`
- Modify: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/interop/JamesDspBaseEngine.kt`
- Modify: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/interop/JamesDspLocalEngine.kt`
- Modify: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/interop/JamesDspRemoteEngine.kt`
- Create: `app/src/androidTest/java/me/timschneeberger/rootlessjamesdsp/interop/LiveProgLifecycleInstrumentedTest.kt`

**Interfaces:**
- Consumes: existing `LiveProgStringParserSlot(jdsp, slot, script)`, `LiveProgEnableSlot`, `jdsp->eel`, `jdsp->eelExtra[0..2]`.
- Produces:
  - `LiveProg *LiveProgGetSlot(JamesDSPLib *jdsp, int slot)`; returns null for slots outside `0..JDSP_LIVEPROG_EXTRA`.
  - `int LiveProgSetVariableSlot(JamesDSPLib *jdsp, int slot, const char *name, float value)`.
  - `int LiveProgSetVariable(JamesDSPLib *jdsp, const char *name, float value)` as a slot-0 wrapper.
  - JNI/Kotlin `enumerateEelVariablesSlot(self, slot)` and `manipulateEelVariableSlot(self, slot, name, value)`.
  - Existing `enumerateEelVariables` / `manipulateEelVariable` remain slot-0 aliases.

- [ ] **Step 1: Write the failing slot-isolation instrumentation test**

Create the test class with a callback stub and one test that loads two legacy scripts into slots 0 and 1, verifies both expose an independent `gain` variable, writes slot 1, and confirms slot 0 does not change.

```kotlin
@RunWith(AndroidJUnit4::class)
class LiveProgLifecycleInstrumentedTest {
    private class Callbacks : JamesDspWrapper.JamesDspCallbacks {
        override fun onLiveprogOutput(message: String) = Unit
        override fun onLiveprogExec(id: String) = Unit
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) = Unit
        override fun onVdcParseError() = Unit
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) = Unit
    }

    private lateinit var handle: JamesDspHandle

    @Before fun setUp() {
        handle = JamesDspWrapper.alloc(Callbacks())
        assertTrue(handle != 0L)
        JamesDspWrapper.setSamplingRate(handle, 48000f, false)
    }

    @After fun tearDown() {
        JamesDspWrapper.free(handle)
    }

    private fun value(slot: Int, name: String): Float =
        JamesDspWrapper.enumerateEelVariablesSlot(handle, slot)
            .first { it.name == name }.value

    @Test fun slotVariableWritesAreIsolated() {
        val script0 = """
            @init
            gain = 0.25;
            @sample
            spl0 *= gain; spl1 *= gain;
        """.trimIndent()
        val script1 = """
            @init
            gain = 0.75;
            @sample
            spl0 *= gain; spl1 *= gain;
        """.trimIndent()

        assertTrue(JamesDspWrapper.setLiveprog(handle, true, "slot0", script0))
        assertTrue(JamesDspWrapper.setLiveprogSlot(handle, 1, true, "slot1", script1))
        assertEquals(0.25f, value(0, "gain"), 0.0001f)
        assertEquals(0.75f, value(1, "gain"), 0.0001f)

        assertTrue(JamesDspWrapper.manipulateEelVariableSlot(handle, 1, "gain", 0.5f))
        assertEquals(0.25f, value(0, "gain"), 0.0001f)
        assertEquals(0.5f, value(1, "gain"), 0.0001f)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails before the APIs exist**

Run on a connected Android device/emulator:

```bash
./gradlew connectedRootlessFdroidDebugAndroidTest --no-daemon
```

Expected: compilation fails because `enumerateEelVariablesSlot` and `manipulateEelVariableSlot` do not exist.

- [ ] **Step 3: Extend `LiveProg` state and expose a safe slot accessor**

In `jdsp_header.h`, extend the structure now so later tasks do not churn ABI repeatedly:

```c
typedef struct
{
    NSEEL_VMCTX vm;
    NSEEL_CODEHANDLE codehandleInit, codehandleSlider, codehandleBlock, codehandleProcess;
    float *vmFs, *samplesBlock, *input1, *input2;
    int compileSucessfully;
    int active;
} LiveProg;
```

Declare:

```c
extern LiveProg *LiveProgGetSlot(JamesDSPLib *jdsp, int slot);
extern int LiveProgSetVariableSlot(JamesDSPLib *jdsp, int slot, const char *name, float value);
extern int LiveProgSetVariable(JamesDSPLib *jdsp, const char *name, float value);
```

In `liveprogWrapper.c`, replace the current fallback-to-slot-0 behavior with strict validation:

```c
LiveProg *LiveProgGetSlot(JamesDSPLib *jdsp, int slot)
{
    if (!jdsp || slot < 0 || slot > JDSP_LIVEPROG_EXTRA)
        return 0;
    if (slot == 0)
        return &jdsp->eel;
    return &jdsp->eelExtra[slot - 1];
}
```

Update all existing slot helpers to reject invalid slots rather than silently touching slot 0.

- [ ] **Step 4: Register and initialize the future lifecycle fields for every VM**

In constructors/reload setup, initialize `codehandleSlider`, `codehandleBlock`, and `samplesBlock`; register `samplesblock` with NSEEL and set it to zero. Destructors must free all four handles and null all pointers/handles.

```c
pg->codehandleSlider = 0;
pg->codehandleBlock = 0;
pg->samplesBlock = NSEEL_VM_regvar(pg->vm, "samplesblock");
if (pg->samplesBlock)
    *pg->samplesBlock = 0.0f;
```

- [ ] **Step 5: Add native slot-aware variable mutation**

Implement validation and locking exactly once in native code:

```c
int LiveProgSetVariableSlot(JamesDSPLib *jdsp, int slot, const char *name, float value)
{
    if (!jdsp || !name || !*name || !isfinite(value))
        return 0;
    const size_t len = strlen(name);
    if (len > NSEEL_MAX_VARIABLE_NAMELEN ||
        !(isalpha((unsigned char)name[0]) || name[0] == '_'))
        return 0;
    for (size_t i = 1; i < len; ++i)
        if (!(isalnum((unsigned char)name[i]) || name[i] == '_'))
            return 0;

    jdsp_lock(jdsp);
    LiveProg *pg = LiveProgGetSlot(jdsp, slot);
    float *var = (pg && pg->vm && pg->compileSucessfully)
        ? NSEEL_VM_getvar(pg->vm, name) : 0;
    if (!var) {
        jdsp_unlock(jdsp);
        return 0;
    }
    *var = value;
    if (pg->codehandleSlider)
        NSEEL_code_execute(pg->codehandleSlider);
    jdsp_unlock(jdsp);
    return 1;
}

int LiveProgSetVariable(JamesDSPLib *jdsp, const char *name, float value)
{
    return LiveProgSetVariableSlot(jdsp, 0, name, value);
}
```

- [ ] **Step 6: Add slot-aware JNI/Kotlin APIs and preserve slot-0 aliases**

Add Kotlin declarations:

```kotlin
external fun enumerateEelVariablesSlot(
    self: JamesDspHandle,
    slot: Int,
): ArrayList<EelVmVariable>

external fun manipulateEelVariableSlot(
    self: JamesDspHandle,
    slot: Int,
    name: String,
    value: Float,
): Boolean
```

Refactor native enumeration so both methods share a helper taking the selected `LiveProg *`. Route mutation to `LiveProgSetVariableSlot`; do not directly assign `ctx->varTable_Values` anymore.

In `JamesDspBaseEngine`, add:

```kotlin
abstract fun enumerateEelVariablesSlot(slot: Int): ArrayList<EelVmVariable>
abstract fun manipulateEelVariableSlot(slot: Int, name: String, value: Float): Boolean
```

Local engine delegates to JNI. Remote engine returns `arrayListOf()` / `false`, consistent with `supportsEelVmAccess() == false`. Existing no-slot methods continue to target slot 0.

- [ ] **Step 7: Run the instrumentation test and build**

```bash
./gradlew connectedRootlessFdroidDebugAndroidTest assembleRootlessFdroidDebug --no-daemon
```

Expected: `slotVariableWritesAreIsolated` passes; debug APK builds.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/cpp app/src/main/java app/src/androidTest
git commit -m "feat: add slot-aware LiveProg VM access"
```

---

### Task 2: Replace the two-section parser with the hardened four-section transactional loader

**Files:**
- Modify: `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/liveprogWrapper.c`
- Modify: `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdsp_header.h`
- Modify: `app/src/main/cpp/libjamesdsp-wrapper/JamesDspWrapper.cpp`
- Modify: `app/src/androidTest/java/me/timschneeberger/rootlessjamesdsp/interop/LiveProgLifecycleInstrumentedTest.kt`

**Interfaces:**
- Consumes: `LiveProgGetSlot`, four-handle `LiveProg` state from Task 1.
- Produces:
  - `int LiveProgStringParserSlot(JamesDSPLib *jdsp, int slot, char *source, char *errorBuffer, size_t errorBufferSize)`.
  - `int LiveProgStringParser(JamesDSPLib *jdsp, char *source, char *errorBuffer, size_t errorBufferSize)` slot-0 wrapper.
  - Error codes: `-1 init syntax`, `-2 missing sample`, `-3 sample syntax`, `-4 slider syntax`, `-5 block syntax`, `-6 duplicate section`, `-7 allocation/VM setup`.

- [ ] **Step 1: Add failing parser coverage**

Add tests that attempt to load:

```kotlin
@Test fun acceptsSampleOnlyAndFullLifecycleInEverySlot() {
    val sampleOnly = """
        @sample
        spl0 *= 0.5; spl1 *= 0.5;
    """.trimIndent()

    val full = """
        @init
        gain = 0.5;
        @slider
        derived = gain * 2;
        @block
        block_seen = samplesblock;
        @sample
        spl0 *= gain; spl1 *= gain;
    """.trimIndent()

    assertTrue(JamesDspWrapper.setLiveprog(handle, true, "sampleOnly", sampleOnly))
    for (slot in 1..3)
        assertTrue(JamesDspWrapper.setLiveprogSlot(handle, slot, true, "full$slot", full))
}

@Test fun rejectsMissingSampleAndDuplicateSections() {
    assertFalse(JamesDspWrapper.setLiveprog(handle, true, "missing", "@init\nx=1;"))
    assertFalse(JamesDspWrapper.setLiveprog(handle, true, "dup", "@sample\nx=1;\n@sample\ny=2;"))
}
```

Also add one test where `"@block"` appears inside a line comment and confirm it does not create a section boundary.

- [ ] **Step 2: Run the tests and confirm old parsing fails the new cases**

```bash
./gradlew connectedRootlessFdroidDebugAndroidTest --no-daemon
```

Expected: sample-only/full lifecycle tests fail under the old `strstr` parser.

- [ ] **Step 3: Port the line-aware section splitter**

Add the section enum, `LiveProgSourceSection`, `LiveProgSectionAtLine`, `LiveProgSplitSource`, and `LiveProgCopySection` behavior from the proven 051 implementation. Recognize only section directives appearing as standalone section lines (allow leading whitespace and trailing `//` comment), reject duplicates, and require `@sample`.

The recognized enum must be exactly:

```c
enum {
    LIVEPROG_SECTION_NONE = -1,
    LIVEPROG_SECTION_INIT,
    LIVEPROG_SECTION_SLIDER,
    LIVEPROG_SECTION_BLOCK,
    LIVEPROG_SECTION_SAMPLE,
    LIVEPROG_SECTION_COUNT,
    LIVEPROG_SECTION_OTHER
};
```

- [ ] **Step 4: Implement reusable state initialize/destroy helpers**

Add:

```c
static int LiveProgInitializeState(LiveProg *pg, float sampleRate);
static void LiveProgClearCode(LiveProg *pg);
static void LiveProgDestroyState(LiveProg *pg);
```

`LiveProgInitializeState` must allocate a new VM, register `srate`, `samplesblock`, `spl0`, `spl1`, validate every registration, set sample rate and zero block size, and leave the state safe for destruction on any failure.

- [ ] **Step 5: Compile all four sections independently**

Implement a slot-independent loader operating on a candidate `LiveProg *`:

```c
static int LiveProgLoadCode(
    LiveProg *pg,
    float sampleRate,
    const char *codeTextInit,
    const char *codeTextSlider,
    const char *codeTextBlock,
    const char *codeTextProcess);
```

Compile `@init` with common-function reset flags, compile the optional slider/block sections, compile required sample, execute init then slider, and only then set `compileSucessfully = 1`.

- [ ] **Step 6: Make parser replacement transactional per slot**

The parser must build a candidate VM outside the active slot, then lock only around compile/swap state that requires synchronization. On success:

```c
LiveProg *target = LiveProgGetSlot(jdsp, slot);
LiveProg previous = *target;
candidate.active = previous.active;
*target = candidate;
memset(&candidate, 0, sizeof(candidate));
LiveProgDestroyState(&previous);
```

On failure, destroy the candidate only. Never clear the active slot before the replacement is known-good.

- [ ] **Step 7: Update error reporting and JNI parser calls**

Update `checkErrorCode()` with `-4..-7`. Change both `setLiveprog` and `setLiveprogSlot` JNI calls to pass a local `char errorBuffer[512] = {0};` to the parser and use that buffer for callback/log output.

Remove the existing unconditional `jdsp_unlock(dsp)` workaround following parser calls. The parser must return with balanced lock state on every path.

- [ ] **Step 8: Run parser tests plus slot-isolation regression**

```bash
./gradlew connectedRootlessFdroidDebugAndroidTest --no-daemon
```

Expected: parser tests and Task 1 isolation test pass.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/cpp app/src/androidTest
git commit -m "feat: add transactional four-section LiveProg parser"
```

---

### Task 3: Implement real `@block` and `@slider` lifecycle semantics for every slot

**Files:**
- Modify: `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/liveprogWrapper.c`
- Review: `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdspController.c`
- Modify: `app/src/androidTest/java/me/timschneeberger/rootlessjamesdsp/interop/LiveProgLifecycleInstrumentedTest.kt`

**Interfaces:**
- Consumes: compiled slider/block handles, `LiveProgSetVariableSlot`, `samplesBlock` from Tasks 1–2.
- Produces: exact lifecycle execution ordering for slots 0–3.

- [ ] **Step 1: Add a failing lifecycle-counter test for all four slots**

Use one script per slot with observable counters:

```kotlin
private val lifecycleScript = """
    @init
    init_count = 0; slider_count = 0; block_count = 0; sample_count = 0;
    gain = 1;
    init_count += 1;

    @slider
    slider_count += 1;
    derived_gain = gain * 0.5;

    @block
    block_count += 1;
    seen_block_size = samplesblock;

    @sample
    sample_count += 1;
    spl0 *= derived_gain;
    spl1 *= derived_gain;
""".trimIndent()
```

For each slot `0..3`: load/enable script, assert `init_count == 1` and `slider_count == 1`, manipulate `gain` in that slot and assert slider count becomes `2`, process a 16-frame stereo float buffer, then assert `block_count == 1`, `seen_block_size == 16`, and `sample_count == 16`.

For slot loading use `setLiveprog` for 0 and `setLiveprogSlot` for 1–3.

- [ ] **Step 2: Run and confirm block/sample lifecycle assertions fail before runtime changes**

```bash
./gradlew connectedRootlessFdroidDebugAndroidTest --no-daemon
```

Expected: compilation may now succeed, but `block_count`, `seen_block_size`, or slider behavior fails because `LiveProgProcessSlot` still only executes sample code.

- [ ] **Step 3: Execute `@block` exactly once per slot process call**

Update `LiveProgProcessSlot`:

```c
void LiveProgProcessSlot(JamesDSPLib *jdsp, int slot, size_t n)
{
    LiveProg *eel = LiveProgGetSlot(jdsp, slot);
    if (!eel || !eel->compileSucessfully || !eel->active)
        return;

    *eel->samplesBlock = (float)n;
    if (eel->codehandleBlock)
        NSEEL_code_execute(eel->codehandleBlock);

    for (size_t i = 0; i < n; ++i) {
        *eel->input1 = jdsp->tmpBuffer[0][i];
        *eel->input2 = jdsp->tmpBuffer[1][i];
        NSEEL_code_execute(eel->codehandleProcess);
        jdsp->tmpBuffer[0][i] = isfinite((float)*eel->input1) ? (float)*eel->input1 : 0.0f;
        jdsp->tmpBuffer[1][i] = isfinite((float)*eel->input2) ? (float)*eel->input2 : 0.0f;
    }
}
```

Do not allocate memory, parse strings, or take additional locks inside this function.

- [ ] **Step 4: Verify controller dispatch already provides the correct block boundary**

Inspect `jdspDispatchEffect`: slot 0 must call `LiveProgProcess(jdsp, n)` and slots 1–3 must call `LiveProgProcessSlot(jdsp, slot, n)` under the existing controller mutex. If this remains true, make no controller change. If a test demonstrates otherwise, make only the smallest dispatch fix needed to pass the lifecycle test.

- [ ] **Step 5: Run lifecycle tests**

```bash
./gradlew connectedRootlessFdroidDebugAndroidTest --no-daemon
```

Expected: all four slots show `init=1`, `slider=2` after one host write, `block=1`, `seen_block_size=16`, `sample=16`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/cpp app/src/androidTest
git commit -m "feat: execute LiveProg slider and block lifecycle"
```

---

### Task 4: Prove hot-reload safety, slot isolation, and error-stage reporting

**Files:**
- Modify: `app/src/androidTest/java/me/timschneeberger/rootlessjamesdsp/interop/LiveProgLifecycleInstrumentedTest.kt`
- Modify only if tests expose defects: `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/liveprogWrapper.c`
- Modify only if tests expose defects: `app/src/main/cpp/libjamesdsp-wrapper/JamesDspWrapper.cpp`

**Interfaces:**
- Consumes: transactional parser and slot-aware lifecycle.
- Produces: regression coverage for candidate VM replacement and independent slot state.

- [ ] **Step 1: Add failed-reload preservation test**

```kotlin
@Test fun invalidReloadDoesNotDestroyRunningScript() {
    val good = """
        @init
        gain = 0.25;
        @sample
        spl0 *= gain; spl1 *= gain;
    """.trimIndent()
    val bad = """
        @init
        gain = 0.75;
        @slider
        broken = ;
        @sample
        spl0 *= gain; spl1 *= gain;
    """.trimIndent()

    assertTrue(JamesDspWrapper.setLiveprog(handle, true, "good", good))
    assertFalse(JamesDspWrapper.setLiveprog(handle, true, "bad", bad))
    assertEquals(0.25f, value(0, "gain"), 0.0001f)

    val input = floatArrayOf(1f, 1f, 1f, 1f)
    val output = FloatArray(input.size)
    JamesDspWrapper.processFloat(handle, input, output, 0, input.size)
    assertEquals(0.25f, output[0], 0.0001f)
    assertEquals(0.25f, output[1], 0.0001f)
}
```

- [ ] **Step 2: Add targeted-slider isolation test with four simultaneous scripts**

Load lifecycle scripts into slots 0–3, snapshot each `slider_count`, write `gain` only in slot 2, and assert only slot 2 increments. Then process one block and assert all enabled slots independently increment their block/sample counters.

- [ ] **Step 3: Add stage-specific syntax failure tests**

For each stage, load a script with one deliberate syntax error and verify `setLiveprog` returns false:

```text
@init   -> -1
@slider -> -4
@block  -> -5
@sample -> -3
```

Use a callback implementation that records `onLiveprogResult(resultCode, id, errorMessage)` and assert the expected code is delivered for each script.

- [ ] **Step 4: Run the new tests and fix only demonstrated defects**

```bash
./gradlew connectedRootlessFdroidDebugAndroidTest --no-daemon
```

Expected: hot reload preserves the old VM, slider writes are slot-local, stage-specific result codes match the contract.

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest app/src/main/cpp
git commit -m "test: harden multi-slot LiveProg lifecycle"
```

---

### Task 5: Locking and compatibility audit

**Files:**
- Review/modify: `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/liveprogWrapper.c`
- Review/modify: `app/src/main/cpp/libjamesdsp-wrapper/JamesDspWrapper.cpp`
- Review: `app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdspController.c`
- Modify: `app/src/androidTest/java/me/timschneeberger/rootlessjamesdsp/interop/LiveProgLifecycleInstrumentedTest.kt`

**Interfaces:**
- Consumes: completed implementation from Tasks 1–4.
- Produces: balanced mutex ownership and compatibility proof for legacy scripts / non-finite sanitation.

- [ ] **Step 1: Search for stale manual unlock and direct VM writes**

Run:

```bash
git grep -n "Workaround due to library bug"
git grep -n "varTable_Values.*= value"
git grep -n "jdsp_unlock(dsp)" app/src/main/cpp/libjamesdsp-wrapper/JamesDspWrapper.cpp
```

Expected: no parser workaround remains; no JNI direct variable-table assignment remains. Any remaining unlocks must pair with a lock in the same JNI/native ownership path.

- [ ] **Step 2: Add legacy and non-finite regression tests**

Add a legacy `@init + @sample` script test and a script that deliberately produces `0/0` in both channels. Verify legacy processing still works and non-finite output is sanitized to zero.

```kotlin
@Test fun legacyScriptAndNonFiniteSanitizerRemainCompatible() {
    val legacy = "@init\ngain=0.5;\n@sample\nspl0*=gain; spl1*=gain;"
    assertTrue(JamesDspWrapper.setLiveprog(handle, true, "legacy", legacy))

    val input = floatArrayOf(1f, -1f)
    val output = FloatArray(2)
    JamesDspWrapper.processFloat(handle, input, output, 0, 2)
    assertEquals(0.5f, output[0], 0.0001f)
    assertEquals(-0.5f, output[1], 0.0001f)

    val nanScript = "@sample\nspl0=0/0; spl1=0/0;"
    assertTrue(JamesDspWrapper.setLiveprog(handle, true, "nan", nanScript))
    JamesDspWrapper.processFloat(handle, input, output, 0, 2)
    assertEquals(0f, output[0], 0f)
    assertEquals(0f, output[1], 0f)
}
```

- [ ] **Step 3: Run instrumentation repeatedly to expose lock/state races**

```bash
./gradlew connectedRootlessFdroidDebugAndroidTest --no-daemon
./gradlew connectedRootlessFdroidDebugAndroidTest --no-daemon
./gradlew connectedRootlessFdroidDebugAndroidTest --no-daemon
```

Expected: three consecutive passes, no hang, ANR, native crash, double unlock, or stale VM access.

- [ ] **Step 4: Commit only if this audit required production changes; otherwise commit the regression tests**

```bash
git add app/src/androidTest app/src/main/cpp
git commit -m "test: verify LiveProg locking and legacy compatibility"
```

---

### Task 6: Full build/regression verification and implementation report

**Files:**
- Modify: `docs/superpowers/plans/2026-08-11-liveprog-slider-block.md` only to check completed task boxes during execution.
- Create: `docs/superpowers/reports/2026-08-11-liveprog-slider-block-verification.md`
- No production changes unless verification finds a defect.

**Interfaces:**
- Consumes: complete feature branch.
- Produces: evidence that the branch is merge-ready.

- [ ] **Step 1: Run JVM unit tests**

```bash
./gradlew testRootlessFdroidDebugUnitTest --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Run all rootless F-Droid instrumentation tests on the connected S24 Ultra or emulator**

```bash
./gradlew connectedRootlessFdroidDebugAndroidTest --no-daemon
```

Expected: PASS, including all `LiveProgLifecycleInstrumentedTest` cases.

- [ ] **Step 3: Build the debug APK used for device testing**

```bash
./gradlew assembleRootlessFdroidDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Build the same optimized release variant used by the repository's fork CI**

```bash
./gradlew assembleRootlessFdroidRelease --no-daemon
```

Expected: BUILD SUCCESSFUL. This matches `.github/workflows/build-fork.yml`.

- [ ] **Step 5: Inspect the feature diff for accidental scope expansion**

```bash
git diff --stat viper-extras...HEAD
git diff --check viper-extras...HEAD
```

Expected: changes limited to the design/plan/report, LiveProg native/JNI/Kotlin files, and focused tests; `git diff --check` prints nothing.

- [ ] **Step 6: Write the verification report with exact evidence**

Create `docs/superpowers/reports/2026-08-11-liveprog-slider-block-verification.md` containing:

```markdown
# LiveProg Slider/Block Verification

## Branch
`feature/liveprog-slider-block`

## Lifecycle Contract Verified
- @init: once after successful load
- @slider: once after load and once per targeted host variable write
- @block: once per LiveProgProcessSlot call
- @sample: once per frame
- samplesblock: equals process-call frame count

## Multi-slot Verification
- Slots 0, 1, 2, 3 compile and run independently
- Variable mutation is slot-targeted
- Failed reload preserves the previous valid slot VM
- Reorderable-chain dispatch remains unchanged

## Commands
- `./gradlew testRootlessFdroidDebugUnitTest --no-daemon` — PASS
- `./gradlew connectedRootlessFdroidDebugAndroidTest --no-daemon` — PASS
- `./gradlew assembleRootlessFdroidDebug --no-daemon` — PASS
- `./gradlew assembleRootlessFdroidRelease --no-daemon` — PASS
- `git diff --check viper-extras...HEAD` — PASS
```

Only record PASS after the command actually passes; if any command cannot run, record the exact limitation instead of claiming success.

- [ ] **Step 7: Commit verification artifacts**

```bash
git add docs/superpowers
git commit -m "docs: record LiveProg lifecycle verification"
```

---

## Completion Gate

Do not merge into `viper-extras` until all of the following are evidenced:

- Existing `@init + @sample` scripts load and process unchanged.
- `@sample`-only scripts load.
- All four slots accept and independently execute `@init`, `@slider`, `@block`, `@sample`.
- `samplesblock` equals the `n` passed to `LiveProgProcessSlot`.
- A targeted host variable write triggers only that slot's `@slider`.
- Duplicate sections and missing `@sample` are rejected.
- Syntax failures identify init/slider/block/sample correctly.
- Failed reload preserves the prior valid VM and audio processing.
- Non-finite output sanitation remains functional.
- No parser manual-unlock workaround or JNI direct NSEEL variable-table mutation remains.
- Reorderable DSP-chain behavior remains intact.
- JVM tests, Android instrumentation tests, debug APK build, and rootless F-Droid release build pass.