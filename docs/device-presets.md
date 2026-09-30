# Measured device presets (offline)

The device library is fully offline. Every APK build regenerates the AutoEq
assets from the `third_party/AutoEq` git submodule
(https://github.com/jaakkopasanen/AutoEq, shallow checkout) and ships it in
the APK assets. Browsing, model search and applying a preset all read from the
on-device database; no network access happens.

- **Source:** `third_party/AutoEq/results/<source>/<measurement rig and form factor>/<model>/<model> ParametricEQ.txt`.
  The `<form>` segment supplies the category: names containing `in-ear` are
  IEMs/sealed wireless buds, `earbud` are unsealed/open-ear models, everything
  else (over-ear rigs) is headphones/studio. These categories describe the
  source's measurement form factor, not a complete connection-type database.
  Each result keeps its original measurement source.
- **Build:** `scripts/build_autoeq_pack.py` scans the submodule and writes
  `app/build/generated/autoeq/autoeq_index.aeq` (searchable index: 8.8k rows,
  ~1.4MB), `autoeq_blobs.aeq` (concatenated filter texts, ~4.2MB) plus
  `autoeq_rev.txt` with the submodule commit SHA. The `:app:buildAutoEqDb`
  Gradle task runs on every build (`preBuild` dependency) so the APK always
  carries the current checkout. The blobs asset is packaged uncompressed
  (`noCompress "aeq"`) so presets are read on demand with positioned reads
  straight from the APK.
- **Runtime:** `AutoEqRepository` holds nothing while the browser UI is closed.
  Opening it loads the 1.4MB index (`acquire`), applying a preset reads only
  that preset's ~1KB slice from the APK (`pread`, never into a copy on disk),
  and leaving the screen (`release`) drops the index and closes the handle.
  Applied filters and device bindings continue to use Aurora's Custom DSP and
  existing settings persistence.

The parser accepts Equalizer APO parametric PK, LS/LSC and HS/HSC filters,
including explicit positive gains. Unsupported active filters, invalid numbers
and profiles larger than Aurora's 12-band capacity fail as a whole instead of
silently applying a partial correction. All 8,850 bundled presets parse under
these rules at the pinned submodule revision.

To update the data, move the submodule to a newer upstream commit
(`git -C third_party/AutoEq fetch origin && git -C third_party/AutoEq checkout <sha>`,
verify the parse count printed by the build script) and rebuild. Cloning with
`--recurse-submodules` pulls the submodule shallow (`shallow = true` is
recorded in `.gitmodules`); the full upstream working tree is ~1.3GB because
it also carries impulse-response WAVs and plots that Aurora never ships.
