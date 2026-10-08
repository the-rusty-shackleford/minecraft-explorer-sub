# Sound sources

The Explorer's one sound is cut from a recording taken from freesound.org under the Creative
Commons Zero (CC0 1.0) public-domain dedication, which permits use, modification and
redistribution without attribution. The recordist is credited here anyway, because they deserve
it. The file in `src/` is the recording as downloaded (Freesound's high-quality Vorbis preview);
`build.py sounds` cuts and loops it into `src/main/resources/assets/explorer_sub/sounds/` with the
shared `tools/sound/cutlib.py`.

| File | Title | Recordist | Freesound page | License |
|---|---|---|---|---|
| `528569-electric-ferry-motor.ogg` | electric ferry motor sound atmo | Garuda1982 | https://freesound.org/people/Garuda1982/sounds/528569/ | CC0 1.0 |

| Shipped sound | Built from |
|---|---|
| `engine.ogg` | 528569, from 58.570 s, 3.110 s long: an electric ferry's motor, a hum on 127 Hz with its harmonics (106, 170, 234, 297 Hz) 20 to 31 dB over the water's wash, from the recording's steadiest stretch (its 100 ms level within half a decibel); the start chosen where the 200 ms crossfade's two ends correlate best (0.65) at equal level. Vanilla Wheels plays it from 0.7 to 1.1 of its pitch: about 90 Hz at idle, a deep electric hum, as a research submarine's electric thrusters |

Chosen by measurement over a submarine cockpit ambience (465472, a designed mix whose hiss carries
more than its hum) and a vintage film submarine engine (438731, the Scout's).
