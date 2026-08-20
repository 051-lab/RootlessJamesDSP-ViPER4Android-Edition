# LiveProg EEL Authoring Guide

This guide is the compatibility contract for custom `.eel` scripts in RootlessJamesDSP and RootlessViPER4Android.

## Default compatibility target

Write scripts for the legacy two-stage LiveProg loader unless a script explicitly requires the enhanced four-stage engine.

```eel
desc: Custom Effect
//tags: custom

gainDb:0<-18,18,0.1>Input gain (dB)
mode:0<0,1,1{Off,On}>Mode

@init
gainDb = 0;
mode = 0;
// Allocate memory, initialize state, define functions, and calculate coefficients.

@sample
// Process spl0 and spl1.
spl0 = spl0;
spl1 = spl1;
```

## Required rules

- Put one `desc:` line first.
- Put metadata declarations before `@init`.
- Give every declared parameter a literal assignment in `@init`:
  `parameter = numeric_literal;`
- Keep parameter names consistent between metadata, assignments, and DSP code.
- Use finite defaults, `min < max`, and positive steps.
- List parameters use integer values and options indexed from zero.
- Include exactly one `@init` and one `@sample` section.
- Keep all initialization, functions, memory allocation, and coefficient setup in `@init`.
- Keep per-sample processing in `@sample`.
- Process both `spl0` and `spl1` unless the effect intentionally documents another behavior.

## Legacy compatibility restrictions

Do not use these sections in a legacy-compatible script:

- `@slider`
- `@block`
- `@serialize`
- `@gfx`

The legacy loader treats unrecognized section markers as EEL code and reports syntax errors. Parameter changes are persisted by rewriting the source assignment and reloading the script.

The enhanced ViPER engine supports `@slider` and `@block`, but using them makes a script incompatible with older installed RJDSP builds. Use those sections only when the script's minimum supported application is explicitly the enhanced engine.

## Parameter defaults and current values

The metadata default is the restore-default value. The literal assignment is the current value loaded by the engine. They may intentionally differ in a saved preset, but both must be valid:

```eel
amount:50<0,100,1>Amount (%)

@init
amount = 75;
```

## Common mistakes

- Declaring `slider1` but only assigning an alias such as `gain = slider1;`.
- Omitting the literal parameter assignment.
- Putting initialization after `@sample`.
- Adding an empty `@slider` or `@block` section.
- Defining duplicate lifecycle sections.
- Using unsupported host-specific functions without testing on the target engine.
- Assuming the metadata declaration itself initializes the EEL variable.
- Allocating buffers from an unchecked parameter value.

## Validation

The repository contains an Android instrumentation test that loads, parses, and processes the EELVault custom fixtures:

```text
me.timschneeberger.rootlessjamesdsp.interop.EelVaultCompatibilityInstrumentedTest
```

Run it against a connected device with the rootless F-Droid debug variant. Keep the fixtures synchronized with the scripts being changed. A script is not considered validated until it:

1. Parses the expected number of properties.
2. Loads into the native LiveProg engine.
3. Processes an audio buffer without an error.
4. Uses the required lifecycle sections for its compatibility target.

## Reference scripts

- Pure DSP: `anima/anima.eel`
- Numeric and list controls: `soloconsole/soloconsole.eel`
- Stateful material effect: `materialmemory/materialmemory.eel`
- Spatial effect with named controls: `stillroom/stillroom.eel`
- Shipped two-stage examples: `app/src/main/assets/Liveprog/`
