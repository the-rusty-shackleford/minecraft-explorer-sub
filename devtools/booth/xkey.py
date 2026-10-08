# Copyright (C) 2026 Rusty Shackleford and nfx. SPDX-License-Identifier: AGPL-3.0-or-later
"""Press or let go of one key on the booth's display through XTEST, as a person would.

Why: NeoForge reads Shift, Control and Alt from GLFW's own key state, which only a real key
event changes. A press handed straight to Minecraft's key handler leaves that state alone, so
the booth's check that Descend works under Left Shift would pass on the code that broke it.

Usage: DISPLAY=:7 uv run --no-project --with python-xlib python xkey.py down|up KEYSYM

Effects: sends one fake press (down) or release (up) of KEYSYM, an X keysym name such as
Shift_L, space or g, and exits 0. Exits 2 without sending on a display that has a window
manager: that is a desktop someone is using, and the booth's Xephyr has none. Exits 1 on a
keysym the display has no key for.
"""
import sys

from Xlib import X, XK, display
from Xlib.ext import xtest


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ('down', 'up'):
        sys.exit('usage: xkey.py down|up KEYSYM')
    d = display.Display()
    wm = d.screen().root.get_full_property(d.intern_atom('_NET_SUPPORTING_WM_CHECK'), X.AnyPropertyType)
    if wm is not None:
        print(f'xkey: {d.get_display_name()} has a window manager; only a booth display may be typed on',
              file=sys.stderr)
        sys.exit(2)
    keysym = XK.string_to_keysym(sys.argv[2])
    keycode = d.keysym_to_keycode(keysym) if keysym else 0
    if not keycode:
        print(f'xkey: no key for {sys.argv[2]!r}', file=sys.stderr)
        sys.exit(1)
    xtest.fake_input(d, X.KeyPress if sys.argv[1] == 'down' else X.KeyRelease, keycode)
    d.sync()


main()
