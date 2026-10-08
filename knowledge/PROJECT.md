---
title: Explorer — project
type: overview
layer: store
tags: [overview]
---

# Explorer

## What this is

nfx's four-seat research submarine (his brief, 2026-10-07, kept in Submersibles'
`knowledge/sources/`): data only, nesting Submersibles (which nests Vanilla Wheels). The plan is
`~/.claude/plans/peppy-scribbling-lollipop.md`, step 3; D-0001 is the first model, the numbers, the
hold and the sound; D-0002 the shape it has now, a personal submersible (Rusty, 2026-10-07: "more of a
slick personal submersible, less of a military submarine").

## Status: 1.0.0 built and verified 2026-10-07, not released

- Gate on D-0001's shape: 8 GameTests and the booth (17 checks) green. Three mutations of the
  shipped data each failed a test: the recipe taking any hull, the stern's hit box removed, the draft
  changed. The hit-box mutation first passed unnoticed, because the hatch test turned the submarine
  30 degrees and the body's own square box happened to cover the hatch there.
- D-0002's shape: 8 GameTests and the booth (17 checks) green, and the three mutations caught again
  (the hatch test aimed at the hatch's after end, the only part only the tail's box covers).
- Not yet: Rusty's look at the booth photos (`run/booth/screenshots/`, wiki images in `wiki/img/`);
  the 4070 playtest with Immersive Aircraft and Man of Many Planes, to tune the feel and fit the
  real upgrades; the release, on Rusty's go, with the Set It Down fixes he queued for the same pack.
- Needs Vanilla Wheels 1.13.0 with the cockpit's glass (its D-0031), Submersibles 1.0.0 with the
  torpedo's launch sound; both unreleased.

## Shape

`devtools/art/build.py` writes every file under `src/main/resources`; edit it, never the outputs.
The model is a voxel shell (`../tools/bbgen/voxel.py`), judged by rendering it with
`../tools/bbgen/render.py` (a cutaway of one half shows the cabin) and in the booth.
