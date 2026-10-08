# Explorer

A research submarine for [Submersibles](https://github.com/the-rusty-shackleford/minecraft-submersibles),
the submarine protocol layered on [Vanilla Wheels](https://github.com/the-rusty-shackleford/minecraft-vanilla-wheels),
on NeoForge 1.21.1: a slick personal submersible for four (Rusty's call over the first, Alvin-like
shape: D-0002), on nfx's brief of twin floods, a visible hold hatch and two stern props, slow and
tough. One block a metre: a low white hull 4.9 long under a glass bubble, two pontoons along its
sides (5.3 to their ducts, 2.6 across), 2.2 to the bubble's crown. There is no Java in it: the
submarine is two datapack profiles, a Blockbench mesh and a sound, and the protocols do the rest,
so diving, the fit-out and torpedoes are documented in Submersibles' README and the vehicle's
keys, fuel, paint and repairs in Vanilla Wheels'.

**1.0.0** is built and verified, not released.

## What it is

- **Four aboard, two by two** under the bubble, the pilot in front on the left, every eye clear of
  the hull's shoulder so everyone sees out all round. The bubble is glass named in the profile's
  `cockpit` too, so from inside it is not drawn and the view out is clear; everyone outside sees it
  (Vanilla Wheels' D-0031). Riders get out through the hatch in its crown.
- **The hold:** a double chest's three rows at half size, hidden in the solid tail under the hatch
  on the deck behind the bubble. The body's own hit box stands to the deck's height only, so a
  click aimed down at the hatch lands there and reaches the chest (from a box as high as the bubble
  it was past Vanilla Wheels' 1.5-block reach); the inventory key opens it from any seat.
- **Two pontoons**, white with a teal stripe (the one colour a dye leaves): the **twin floods** in
  their noses (Vanilla Wheels' headlamps, range 24), a ducted screw at each tail, the two turning
  opposite ways, skids under them. Two torpedo tubes under the chin.
- **The fit-out:** four upgrade slots and two weapon slots.
- **At the surface** it floats with 0.40 of its height under water: the waterline just over the
  pontoons, half a metre of white hull and the whole bubble out.

## Getting one

- **The hull**, at a crafting table: steel blocks round three glass blocks and a chest
  (`S G S / G C G / S S S`).
- **The submarine**: the hull and an engine at a crafting table give the packed Explorer (nfx's call:
  no workshop). Only the Explorer's own hull matches (the ingredient checks the chassis's
  `vanillawheels:vehicle` component through `neoforge:components`; every vehicle's hull is the same
  item). A Mechanic Lift builds one too, on land.
- Right-click open water with it to set it down floating.

## Numbers

In Submersibles' terms (`data/explorer_sub/submersibles/submarine/explorer.json`), from nfx's
proposed stats:

| | |
|---|---|
| top speed | 0.011 thrust, 0.016 slip + 0.032 hull drag: 0.22 blocks a tick |
| climb and dive | 0.08 a tick |
| turn | 1.8 degrees a tick |
| spool | 30 ticks at acceleration 0.5: three seconds |
| fuel | 36 000 ticks, burnt at 1.2 a tick while driven: 25 minutes |
| durability | 4: a quarter of the wear |
| crashes | safe below 0.18 a tick, a wreck at 1.0 |
| currents | 0.4 of a swimmer's push; stabilizer 0.15; tilts up to 8 degrees |
| repair | steel ingots, 24 for a wreck |

## How it is made

`devtools/art/build.py` writes everything: the model, both profiles, the recipes and their unlocks,
the lang and `sounds.json`. Run from the repository root:

```
uv run --no-project --with numpy python devtools/art/build.py
uv run --no-project --with numpy python devtools/art/build.py sounds    # needs ffmpeg
```

The hull and its bubble are a voxel shell (the shared `minecraft mods/tools/bbgen/voxel.py`): a
low superellipse section swept along a rounded bow, a body and a tail tapering between the pontoons,
under an ellipsoid bubble, on a lattice of 2, 1 and 2 voxels (across, up, along), hollowed in three
dimensions through the cabin, glazed wherever the bubble stands clear of the hull, merged into 540
boxes with every hidden face left out. Fittings are boxed outside the hull's envelope, so nothing
stands in the cabin. The folders the profiles select by: `paint` (dyed: the hull, the pontoons, the
web), `glass` (translucent, and the `cockpit`: not drawn from a rider's own eyes), `lamps` (lit
with the floods), `propeller_left` and `propeller_right` (spun by Submersibles). The first shape,
Alvin's (D-0001), drew on public-domain references (`devtools/art/reference/SOURCES.md`). The
engine loop is cut from a CC0 recording of an electric ferry's motor
(`devtools/art/sounds/SOURCES.md`): a hum on 127 Hz, played from 0.7 to 1.1 of its pitch.

The model is judged with `../tools/bbgen/render.py` (offline) and the booth (in game).

## Verifying it

```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew clean build              # gametests and the booth (needs a display; -PskipBooth)
```

Submersibles and Vanilla Wheels come from Maven Local (`./gradlew publishToMavenLocal` in Vanilla
Wheels, then Submersibles). Eight gametests in a tank of water: the profiles; the recipes, and
another vehicle's hull refused; a crafted Explorer set down on open water floats at its draft; it
holds its depth and rises to float; it runs up to its own top speed; four ride and breathe at depth
and a fifth is turned away; the hold opens through the deck hatch and from a seat; a torpedo leaves
each tube under the chin. The booth films it and checks seventeen things a client shows (the
pilot's and a back seat's view out through the bubble, the floods at night, a real Left Shift
diving it, R at the crown's hatch, the fit-out's slots, the hold).

## License

AGPL-3.0-or-later. Copyright Rusty Shackleford and nfx.
