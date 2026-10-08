"""The Explorer, as code: its model (a personal submersible for four, a block a metre), its two
profiles, its recipes, its lang and its sounds' entries.

Run from the repository root:

    uv run --no-project --with numpy python devtools/art/build.py

Everything it writes is committed; this script is the source of truth for those files. nfx's brief
asked for a four-seat research sub with twin floods, a visible hold hatch and two stern props; Rusty
(2026-10-07) wanted "more of a slick personal submersible, less of a military submarine" than its
first, Alvin-like shape (D-0002). The model is our own. Copyright 2026 Rusty Shackleford and nfx.
SPDX-License-Identifier: AGPL-3.0-or-later.

The body and its bubble are a voxel shell (the shared minecraft mods/tools/bbgen/voxel.py): a low
superellipse hull swept along a rounded bow, a body and a tapering tail, under an ellipsoid bubble;
hollow through the cabin, glazed wherever the bubble stands clear of the body, so the four aboard
sit two by two looking out all round. Two graphite pontoons run along its sides, the floods in their
noses and a ducted screw at each tail; aft of the cabin the body is solid, the hold's chest hidden in
it under the deck hatch.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT.parent / "tools/bbgen"))
import bbgen  # noqa: E402  (the shared Blockbench writer: minecraft mods/tools/bbgen)
import voxel  # noqa: E402

NS = "explorer_sub"
NAME = "explorer"
ASSETS = ROOT / "src/main/resources/assets" / NS
DATA = ROOT / "src/main/resources/data" / NS

PX = 16
ORIGIN = 42 / PX            # the model's origin: 2.625 m aft of the bow, on the keel line

# ------------------------------------------------------------------ the body

LENGTH = 4.90               # the bow to the tail's end, between the pontoons
CENTRE = 0.85               # the body's axis, metres up
W0, H0 = 0.92, 0.55         # its half-beam and half-height amidships: low, a boat's under the bubble
N = 2.4                     # the section's superellipse
BOW, STERN = 1.30, 3.60     # where the bow's curve ends and the tail's taper begins
CABIN = (0.0, 3.15)         # the stations the inside is hollow between: the bow to the bulkhead
FLOOR_Y = 0.55              # the cabin floor's top
CELL = (2, 1, 2)            # the lattice, voxels: 0.125 m steps across and along, a pixel up and down
DOME = ((0.0, 1.15, 1.60), (0.88, 1.05, 1.60))     # the bubble's middle (x, y, s) and its radii: its crown at 2.20, inside the body's beam
PONTOON_X, PONTOON_Y = 0.98, 0.46                  # the pontoons' axes, each side
PONTOON = (0.60, 5.00, 0.20, 0.22)                 # from, to (stations); half-width, half-height


def section(s):
    """The body's half-beam and half-height at stations s (an array), metres: a rounded bow, the body, a taper."""
    s = np.asarray(s, dtype=float)
    f = np.zeros_like(s)
    bow = (s >= 0) & (s < BOW)
    f[bow] = (1 - ((BOW - s[bow]) / BOW) ** 2.4) ** (1 / 2.4)
    f[(s >= BOW) & (s <= STERN)] = 1.0
    st = (s > STERN) & (s <= LENGTH)
    f[st] = 1 - 0.70 * ((s[st] - STERN) / (LENGTH - STERN)) ** 1.3
    return W0 * f, H0 * f


def in_body(X, Y, S):
    w, h = section(S)
    w, h = np.maximum(w, 1e-9), np.maximum(h, 1e-9)
    return (np.abs(X / w) ** N + np.abs((Y - CENTRE) / h) ** N <= 1.0) & (w > 1e-6)


def in_dome(X, Y, S):
    """The bubble, its lower half cut away at the body's waist: below it the body is the cabin's floor and sides."""
    (cx, cy, cs), (rx, ry, rs) = DOME
    return (((X - cx) / rx) ** 2 + ((Y - cy) / ry) ** 2 + ((S - cs) / rs) ** 2 <= 1.0) & (Y >= CENTRE)


def pontoon_scale(s):
    """A pontoon's size at stations s, a share of its full section: a rounded nose, the run, a tail tapering to a half."""
    s0, s1, _, _ = PONTOON
    s = np.asarray(s, dtype=float)
    f = np.zeros_like(s)
    nose = (s >= s0) & (s < s0 + 0.45)
    f[nose] = np.sqrt(np.clip(1 - ((s0 + 0.45 - s[nose]) / 0.45) ** 2, 0, 1))
    f[(s >= s0 + 0.45) & (s <= 4.00)] = 1.0
    tail = (s > 4.00) & (s <= s1)
    f[tail] = 1 - 0.45 * ((s[tail] - 4.00) / (s1 - 4.00)) ** 1.2
    return f


def in_pontoons(X, Y, S):
    _, _, hw, hh = PONTOON
    f = np.maximum(pontoon_scale(S), 1e-9)
    near = np.abs(np.abs(X) - PONTOON_X)
    return (np.abs(near / (hw * f)) ** 2.2 + np.abs((Y - PONTOON_Y) / (hh * f)) ** 2.2 <= 1.0) & (f > 1e-6)


def body(v: voxel.Voxels) -> None:
    """The shell: the body and the bubble one solid, hollowed through the cabin; glass where the bubble
    stands clear of the body, a dark rim where they meet, the floor across the cabin; the pontoons solid."""
    X, Y, S = v.lattice(CELL)
    shape = np.broadcast_shapes(X.shape, Y.shape, S.shape)
    b = np.broadcast_to(in_body(X, Y, S), shape)
    d = np.broadcast_to(in_dome(X, Y, S), shape)
    solid = b | d
    hollow = voxel.erode(solid) & (S >= CABIN[0]) & (S <= CABIN[1])
    shell = solid & ~hollow
    glass = shell & ~b
    rim = shell & b & voxel.dilate(glass)
    v.add("paint/body", "paint", v.up(shell & b & ~rim, CELL))
    v.add("trim/canopy_rim", "trim", v.up(rim, CELL))
    v.add("glass/bubble", "glass", v.up(glass, CELL))
    v.add("interior/floor", "floor", v.up(hollow & (Y < FLOOR_Y), CELL))
    p = np.broadcast_to(in_pontoons(X, Y, S), shape) & ~solid
    v.add("paint/pontoons", "paint", v.up(p, CELL))
    v.envelope |= v.up(solid | p, CELL)


# ------------------------------------------------------------------ the fittings

FLOODS = [(-PONTOON_X, PONTOON_Y, PONTOON[0]), (PONTOON_X, PONTOON_Y, PONTOON[0])]     # the lamps: the pontoons' noses
TUBES = [(-0.30, 0.27, 0.38), (0.30, 0.27, 0.38)]                                       # the torpedo tubes' muzzles, under the chin
PROP_S = 5.08                                                                            # the screws' hubs' station
HOLD = (0.86, 3.70)                                                                      # the hold chest's floor and its middle station
CROWN = (0.0, DOME[0][1] + DOME[1][1], DOME[0][2])                                     # the bubble's top
HATCH_TOP = (0.0, CROWN[1] + 0.20, CROWN[2])                                           # where a rider getting out comes up


def lamps(v: voxel.Voxels) -> None:
    """The floods: each pontoon's nose, its foremost voxels within a lamp's radius, lit, in a dark ring."""
    X, Y, S = v.coords()
    r2 = (np.abs(X) - PONTOON_X) ** 2 + (Y - PONTOON_Y) ** 2
    front = np.broadcast_to(S < PONTOON[0] + 0.30, v.shape)
    v.move("paint/pontoons", "lamps/floods", "lamp", front & (r2 <= 0.10 ** 2))
    v.move("paint/pontoons", "trim/flood_rings", "trim", front & (r2 > 0.10 ** 2) & (r2 <= 0.14 ** 2))
    # A stripe along each pontoon's outer flank, a little over its axis: the one colour a dye leaves.
    band = (np.abs(X) > PONTOON_X + 0.05) & (Y >= PONTOON_Y + 0.06) & (Y <= PONTOON_Y + 0.12)
    v.move("paint/pontoons", "accent/stripes", "accent", np.broadcast_to(band & (S > PONTOON[0] + 0.30), v.shape))


def crown(v: voxel.Voxels) -> None:
    """The hatch in the bubble's crown, where a rider gets out: a disc of the glass made a hatch, in a dark ring."""
    X, Y, S = v.coords()
    r2 = X ** 2 + (S - CROWN[2]) ** 2
    top = np.broadcast_to(Y > CROWN[1] - 0.30, v.shape)
    v.move("glass/bubble", "hatch/crown", "hatch", top & (r2 <= 0.19 ** 2))
    v.move("glass/bubble", "trim/crown_ring", "trim", top & (r2 > 0.19 ** 2) & (r2 <= 0.24 ** 2))


def deck(v: voxel.Voxels) -> None:
    """The hold's hatch on the deck behind the bubble, a voxel proud, and its handle; the web joining the
    pontoons to the body; the torpedo tubes under the chin."""
    xs, ss = v.xs, v.ss
    plate = (np.abs(xs[:, None]) <= 0.34) & (ss[None, :] >= 3.35) & (ss[None, :] <= 4.05)
    v.add("hatch/hold", "hatch", voxel.proud(v.solid(), plate, axis=1, side=1))
    handle = (np.abs(xs[:, None]) <= 0.10) & (ss[None, :] >= 3.93) & (ss[None, :] <= 3.99)
    v.add("trim/hold_handle", "trim", voxel.proud(v.solid(), handle, axis=1, side=1))
    v.mirror("paint/web", "paint", 0.60, 0.86, 0.42, 0.52, 1.20, 4.20)
    for x, y, s in TUBES:
        v.box("pontoons/tubes", "pontoon", x - 0.10, x + 0.10, y - 0.09, y + 0.09, s + 0.03, 1.30)
        v.box("trim/muzzles", "trim", x - 0.06, x + 0.06, y - 0.06, y + 0.06, s, s + 0.03)


def thrusters(v: voxel.Voxels) -> list[dict]:
    """Each pontoon's tail: a duct its four fins carry, a screw in it, a swept fin over it; returns the propellers' entries."""
    X, Y, S = v.coords()
    props = []
    for side, name, spin in ((1, "propeller_left", 1.0), (-1, "propeller_right", -1.0)):
        cx = side * PONTOON_X
        r2 = (X - cx) ** 2 + (Y - PONTOON_Y) ** 2
        v.add("pontoons/ducts", "pontoon", (r2 > 0.25 ** 2) & (r2 <= 0.30 ** 2) & (S >= 4.92) & (S <= 5.24))
        reach = 0.27 * np.clip((S - 4.66) / 0.25, 0, 1)
        along = (S >= 4.66) & (S <= 4.98)
        v.add("pontoons/ducts", "pontoon", (np.abs(X - cx) <= 0.03) & (np.abs(Y - PONTOON_Y) <= reach + 0.02) & along)
        v.add("pontoons/ducts", "pontoon", (np.abs(Y - PONTOON_Y) <= 0.03) & (np.abs(X - cx) <= reach + 0.02) & along)
        v.box("metal/shafts", "metal", cx - 0.04, cx + 0.04, PONTOON_Y - 0.04, PONTOON_Y + 0.04, PONTOON[1] - 0.05, PROP_S + 0.04)
        blades = name + "/blades"
        v.box(blades, "prop", cx - 0.06, cx + 0.06, PONTOON_Y - 0.06, PONTOON_Y + 0.06, PROP_S - 0.06, PROP_S + 0.06, moving=True)
        v.box(blades, "prop", cx - 0.23, cx + 0.23, PONTOON_Y - 0.03, PONTOON_Y + 0.03, PROP_S - 0.03, PROP_S + 0.02, moving=True)
        v.box(blades, "prop", cx - 0.03, cx + 0.03, PONTOON_Y - 0.23, PONTOON_Y + 0.23, PROP_S - 0.02, PROP_S + 0.03, moving=True)
        props.append({"part": {"group": name}, "pivot": mesh(cx, PONTOON_Y, PROP_S), "axis": [0, 0, 1], "speed": spin})
    return props


def skids(v: voxel.Voxels) -> None:
    """Under each pontoon, where it rests ashore."""
    v.mirror("trim/skids", "trim", PONTOON_X - 0.05, PONTOON_X + 0.05, 0.0, 0.09, 1.00, 4.60)
    for s in (1.4, 2.8, 4.2):
        v.mirror("trim/skids", "trim", PONTOON_X - 0.03, PONTOON_X + 0.03, 0.09, 0.30, s, s + 0.07)


# Who sits where: two by two under the bubble, the pilot in front on the left. Hips, and the eye a seated
# rider's 1.0 over them, clear of the body's shoulder: everyone looks out through the glass.
SEATS = [
    {"at": (0.38, 0.65, 1.05), "eye": (0.38, 1.67, 0.98), "driver": True},
    {"at": (-0.38, 0.65, 1.05), "eye": (-0.38, 1.67, 0.98)},
    {"at": (0.38, 0.65, 2.10), "eye": (0.38, 1.67, 2.03)},
    {"at": (-0.38, 0.65, 2.10), "eye": (-0.38, 1.67, 2.03)},
]


def interior(v: voxel.Voxels) -> None:
    """Four seats with low backs, the console ahead of the front row."""
    for s0 in (0.88, 1.93):
        v.mirror("interior/seats", "seat", 0.16, 0.60, FLOOR_Y, 0.65, s0, s0 + 0.36, inside=True)
        v.mirror("interior/seats", "seat", 0.16, 0.60, 0.65, 1.00, s0 + 0.36, s0 + 0.43, inside=True)
    v.box("interior/console", "panel", -0.55, 0.55, 0.86, 1.00, 0.50, 0.60, inside=True)


def mesh(x: float, y: float, s: float) -> list[float]:
    """A point in metres as a profile writes it: mesh pixels, +Z the bow."""
    return [round(x * PX, 3), round(y * PX, 3), round((ORIGIN - s) * PX, 3)]


def make_atlas() -> bbgen.TexelAtlas:
    a = bbgen.TexelAtlas(size=256, density=1.0, seed=0xE5B)
    a.material("paint", (232, 232, 226), w=128, h=64, grain=7)
    a.material("glass", (170, 210, 222), w=64, h=32, grain=4, alpha=96)
    a.material("pontoon", (48, 52, 58), w=64, h=32, grain=6)
    a.material("trim", (34, 36, 40), w=32, h=32, grain=6)
    a.material("accent", (24, 128, 150), w=64, h=16, grain=6)
    a.material("metal", (146, 150, 156), w=32, h=32, grain=10)
    a.material("prop", (186, 148, 74), w=32, h=32, grain=8)
    a.material("lamp", (252, 248, 226), w=16, h=16, grain=3)
    a.material("hatch", (122, 128, 136), w=32, h=32, grain=8,
               pattern=lambda x, y, c: bbgen.shade(c, -24) if x % 8 == 0 or y % 8 == 0 else c)
    a.material("floor", (70, 70, 66), w=32, h=32, grain=10)
    a.material("seat", (214, 200, 176), w=32, h=32, grain=8)

    def dials(x, y, c):
        dx, dy = x % 5 - 2, y % 5 - 2
        if max(abs(dx), abs(dy)) == 2:
            return c
        if max(abs(dx), abs(dy)) == 1:
            return (150, 152, 150) if (dx, dy) != (1, -1) else (226, 226, 214)
        return (12, 12, 14)

    a.material("panel", (30, 32, 34), w=32, h=16, grain=4, pattern=dials, scale=2.0)
    return a


def build_model() -> tuple[bbgen.Model, list[dict]]:
    v = voxel.Voxels(half_width=1.5, height=2.6, length=5.85, origin=ORIGIN, start=-0.25)
    body(v)
    lamps(v)
    crown(v)
    deck(v)
    props = thrusters(v)
    skids(v)
    interior(v)
    m = bbgen.Model(NAME, make_atlas(), seed=f"{NS}/{NAME}")
    print(f"{NAME}: {v.write(m)} boxes")
    return m, props


# ---------------------------------------------------------------- the profiles

def vehicle_profile() -> dict:
    """The Explorer to Vanilla Wheels: its look, its seats, what it rests on beached, its tank, its
    hold, its floods, its paint and glass, its camera and its loop."""
    return {
        "mesh": f"{NS}:{NAME}",
        "scale": 0.0625,
        "handedness": "right",
        # The body's own box stands amidships to the deck's height; the bubble has two boxes of its own,
        # the after one stopping short of the hold's hatch, and the tail one covers the ducts. A click aimed
        # down at the hatch must land at the deck's height: from a box over it, as high as the bubble, the
        # chest under the hatch is past the 1.5 blocks Vanilla Wheels follows a click for one.
        "body": {"width": 2.7, "length": 5.3, "height": 1.55,
                 "parts": [{"at": mesh(0, 0.25, 0.60), "width": 1.9, "height": 2.1},
                           {"at": mesh(0, 0.30, 2.35), "width": 1.7, "height": 2.0},
                           {"at": mesh(0, 0.0, 4.45), "width": 2.7, "height": 1.55}]},
        "seats": [dict({"at": mesh(*seat["at"]), "eye": mesh(*seat["eye"])}, **({"driver": True} if seat.get("driver") else {}))
                  for seat in SEATS],
        # The skids' ends under the pontoons: where it rests, beached.
        "wheels": {"radius": 1, "drawn": False,
                   "positions": [{"forward": mesh(0, 0, s)[2], "right": round(side * PONTOON_X * PX, 3)}
                                 for s in (1.10, 4.50) for side in (-1, 1)]},
        "engine": {"max_speed": 0.22, "acceleration": 0.01, "reverse_speed": 0.07, "brake": 0.02},
        "climb": 0.5,
        "mass": 6.0,
        "fuel": {"capacity": 36000},
        # The hold: a double chest's three rows, its long side along the body, hidden under the deck hatch.
        "storage": {"chests": [{"at": mesh(0, HOLD[0], HOLD[1]), "yaw": 90, "scale": 0.5, "rows": 3}]},
        "headlights": {"at": [mesh(*f) for f in FLOODS], "part": {"group": "lamps"}, "range": 24},
        "paint": {"part": {"group": "paint"}, "default": "white", "factory": "#e9e8e1"},
        "glass": {"group": "glass"},
        "cockpit": {"group": "glass"},   # the bubble: glass to everyone else, clear to whoever looks out through it (Vanilla Wheels D-0031)
        "sounds": {"engine": f"{NS}:engine", "pitch": [0.7, 1.1], "volume": [0.3, 0.8]},
        "camera": 10.0,
        "repair": {"ingredient": {"tag": "c:ingots/steel"}, "full_cost": 24},
    }


#       x0     x1     y0    y1    s0    s1
HULL = [(-0.95, 0.95, 0.28, 2.20, 0.00, 3.20),     # the cabin and its bubble
        (-0.92, 0.92, 0.28, 1.47, 3.20, 4.95),     # the tail, its hatch on it
        (0.68, 1.30, 0.00, 0.80, 0.60, 5.24),      # the left pontoon, its skid and duct
        (-1.30, -0.68, 0.00, 0.80, 0.60, 5.24)]    # the right


def submarine_profile(props: list[dict]) -> dict:
    """The Explorer to Submersibles: slow, tough and heavy (nfx's numbers, in this protocol's terms)."""
    return {
        # Top speed 0.011 * (1 - 0.048) / 0.048 = 0.22 blocks a tick, 4.4 a second.
        "properties": {"engineSpeed": 0.011, "friction": 0.016, "verticalSpeed": 0.08, "yawSpeed": 1.8,
                       "acceleration": 0.5, "fuel": 1.2, "durability": 4.0, "stabilizer": 0.15, "wind": 0.4},
        "hull_drag": 0.032,
        "spool_ticks": 30,
        "rise": 0.05,
        "draft": 0.40,              # surfaced, the waterline just over the pontoons: half a metre of hull and the bubble out
        "tilt": 8,
        "hull": [{"from": mesh(x0, y0, s1), "to": mesh(x1, y1, s0)} for x0, x1, y0, y1, s0, s1 in HULL],
        "propellers": props,
        "upgrades": 4,
        "weapons": [mesh(*t) for t in TUBES],
        "hatch": mesh(*HATCH_TOP),
        "crash": {"safe": 0.18, "touchdown_safe": 0.18, "wreck": 1.0},
    }


# ---------------------------------------------------------------- the recipes

CHASSIS = {"vanillawheels:vehicle": f"{NS}:{NAME}"}

# The hull, at a crafting table: steel round three windows and the hold.
CHASSIS_RECIPE = {
    "type": "minecraft:crafting_shaped", "category": "misc",
    "pattern": ["SGS", "GCG", "SSS"],
    "key": {"G": {"tag": "c:glass_blocks"}, "C": {"item": "minecraft:chest"}, "S": {"tag": "c:storage_blocks/steel"}},
    "result": {"id": "vanillawheels:chassis", "count": 1, "components": CHASSIS},
}

# And the submarine itself, packed, from the hull and an engine: nfx's crafting table, no workshop.
# Only the Explorer's own chassis will do (another vehicle's is the same item).
SUB_RECIPE = {
    "type": "minecraft:crafting_shapeless", "category": "misc",
    "ingredients": [{"type": "neoforge:components", "items": "vanillawheels:chassis", "components": CHASSIS},
                    {"item": "vanillawheels:engine"}],
    "result": {"id": "vanillawheels:vehicle", "count": 1, "components": CHASSIS},
}


def unlock(recipe: str, item: str) -> dict:
    """The recipe-book unlock: on holding what starts it, so a pooled crafting fill finds it."""
    return {"parent": "minecraft:recipes/root",
            "criteria": {"has_the_recipe": {"trigger": "minecraft:recipe_unlocked", "conditions": {"recipe": recipe}},
                         "has_it": {"trigger": "minecraft:inventory_changed", "conditions": {"items": [{"items": item}]}}},
            "requirements": [["has_the_recipe", "has_it"]],
            "rewards": {"recipes": [recipe]}}


SOUNDS_JSON = {
    "engine": {"sounds": [{"name": f"{NS}:engine", "attenuation_distance": 32}]},
}

LANG = {
    f"vehicle.{NS}.{NAME}": "Explorer",
}


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    m, props = build_model()
    m.write(ASSETS / "vanillawheels/mesh" / f"{NAME}.bbmodel")
    write_json(DATA / "vanillawheels/vehicle" / f"{NAME}.json", vehicle_profile())
    write_json(DATA / "submersibles/submarine" / f"{NAME}.json", submarine_profile(props))
    write_json(DATA / "recipe" / f"{NAME}_chassis.json", CHASSIS_RECIPE)
    write_json(DATA / "recipe" / f"{NAME}.json", SUB_RECIPE)
    write_json(DATA / "advancement/recipes" / f"{NAME}_chassis.json", unlock(f"{NS}:{NAME}_chassis", "#c:storage_blocks/steel"))
    write_json(DATA / "advancement/recipes" / f"{NAME}.json", unlock(f"{NS}:{NAME}", "vanillawheels:chassis"))
    write_json(ASSETS / "lang/en_us.json", LANG)
    if (ASSETS / "sounds/engine.ogg").exists():
        write_json(ASSETS / "sounds.json", SOUNDS_JSON)


SOUND_SRC = ROOT / "devtools/art/sounds/src"


def sounds() -> None:
    """The engine's loop, from the electric ferry's motor: see devtools/art/sounds/SOURCES.md. Needs ffmpeg and numpy (`--with numpy`)."""
    sys.path.insert(0, str(ROOT.parent / "tools/sound"))
    import cutlib  # noqa: E402  (the shared cutter: minecraft mods/tools/sound)
    start, length, fade = 58.57, 3.1104, 0.20
    cutlib.write_ogg(ASSETS / "sounds/engine.ogg",
                     cutlib.loop(SOUND_SRC / "528569-electric-ferry-motor.ogg", start, start + length + fade, fade, 0.8))


if __name__ == "__main__":
    if sys.argv[1:] == ["sounds"]:
        sounds()
    else:
        main()
