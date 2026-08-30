# RootlessViPER4Android v2.5.2

Stabilization release. No new features — this release locks down the recorder
and track lifecycle so long listening sessions cannot crash the audio engine.

## Changes

- **Recorder stop/release now unblocks teardown before the thread join.**
  Stopping the effect engine no longer waits on a recorder thread that is
  itself waiting for teardown, which could deadlock the service on stop.
- **Recorder and track references cannot be reused across teardown.**
  Stale `AudioRecord`/`AudioTrack` handles are invalidated when the engine
  stops, so a late buffer callback can never touch a released object.
- **Reads validate negative, zero, odd, and partial sample counts.**
  Malformed capture requests are rejected or handled explicitly instead of
  producing undefined native behavior.
- **Writes loop until the complete processed buffer is delivered or Android
  reports an error.** Partial `AudioTrack.write` results no longer truncate
  audio output mid-buffer.
- **CI now separates debug-signed previews from signed tagged releases.**
  Branch pushes produce installable debug-signed preview APKs via
  `Build fork APK`; the signed `Build signed app` workflow runs only for
  upstream pushes and release tags, so fork branch pushes stay green.

## Upgrade notes

- Installs directly over v2.5.1 (same signature and package).
- No settings or presets need to change.
