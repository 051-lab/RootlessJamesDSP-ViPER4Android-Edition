# Four-slot LiveProg presets

Starting with preset format **v4**, a saved preset embeds **every occupied
LiveProg slot** — up to four scripts — instead of only the first one. Each
script is stored under its own archive entry (`liveprog1`…`liveprog4`) together
with a per-slot name; on load each script is written back to the external
`Liveprog` directory and its working slot is re-armed, so the processing-chain
order and any gaps in between are preserved.

## One-preset chain install

A single `.tar` preset can carry a full multi-effect chain. For example, the
Airwindows v1.1.0 conservative chain is installed from one preset:

1. Assign the four scripts to the four LiveProg cards in order
   (ChannelX → ToTape9 → Srsly3 → X2Buss), using the multi-select file picker.
2. Save the setup as a preset.
3. Anywhere else (or after a reinstall), load that one preset — all four slots
   come back with their original filenames and byte-identical script contents.

Because the restored script in each slot is byte-identical to the one that was
saved, the chain sounds exactly as it did when the preset was written. If the
destination file already exists (e.g. newer script code the user downloaded
since), the preset only merges the saved control values into it rather than
overwriting newer code.

## Compatibility

- Preset format **v4** requires app version **2.6.0** (version code **90** or
  later); older apps reject these archives cleanly with a “preset too new”
  message rather than mis-restoring them.
- Presets saved as format **v1–v3** (a single embedded `liveprog` script) are
  still loaded by the legacy path and restored into slot 1.
- Historical/reference archives are preserved unchanged; each supported EEL
  processor ships its own versioned archive in EELVault.

## Integrity

The on-device round-trip test (`PresetLiveprogInstrumentedTest`) pins the four
v1.1.0 conservative assets against the published SHA-256 checksums in
`EELVault/releases/v1.1.0/SHA256SUMS`, assigns all four slots, saves, clears,
reloads, and asserts both the slot order and byte-identical content for every
restored file.