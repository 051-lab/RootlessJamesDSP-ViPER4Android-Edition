# RootlessViPER4Android v2.6.0

Feature release: **four-slot LiveProg presets**.

## What's new

- **One preset = one full DSP chain.** A saved preset now embeds **every
  occupied LiveProg slot** (up to four scripts), not just the first. Loading a
  single preset restores all four slots — filenames, processing order, and any
  gaps between them — so an entire Airwindows or custom chain installs from one
  file instead of four.
- **Byte-identical restore.** Each restored script is byte-identical to the one
  that was saved (hash-verified against the published v1.1.0 conservative
  assets), so a restored chain sounds exactly as written. If a newer script
  already exists at the destination, the preset merges only the saved control
  values into it rather than overwriting newer code.
- **Backwards compatible.** Presets saved as format v1–v3 (a single embedded
  script) still restore into slot 1 via the legacy loader.

## Compatibility

- Preset format **v4** requires this release (version code **90** or later).
  Older app versions reject these archives with a clear "preset too new"
  message rather than restoring them incorrectly.
- Historical/reference EELVault archives are preserved unchanged.

See [docs/liveprog-four-slot-presets.md](./liveprog-four-slot-presets.md) for
one-preset chain install instructions and integrity details.