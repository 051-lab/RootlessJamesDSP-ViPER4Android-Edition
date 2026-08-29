# Repository Instructions

When creating or modifying a LiveProg `.eel` script, read `LIVEPROG_EEL_AUTHORING.md` first.

The default compatibility target is the legacy two-stage loader: one `@init` section and one `@sample` section, with literal assignments for every metadata parameter immediately under `@init`. Do not add `@slider` or `@block` unless enhanced-engine-only support is intentional and documented.

Before declaring a script complete, run the EELVault compatibility instrumentation test on a connected device and preserve the EELVault backup when refactoring existing scripts.
