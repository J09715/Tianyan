"""Refresh functional Android icons from a pinned Material Symbols Rounded revision.

Run explicitly with Python 3.10+; this is not part of the Android build and adds no runtime dependency.
Brand/provider logos are intentionally outside this manifest.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from tempfile import gettempdir
from time import sleep
from urllib.error import HTTPError, URLError
from urllib.request import urlopen
from xml.etree import ElementTree as ET
from xml.sax.saxutils import quoteattr

REVISION = "49d4db35df873165d6bd6ba09b063c7dafbac2f4"
BASE = f"https://raw.githubusercontent.com/google/material-design-icons/{REVISION}"
ROOT = Path(__file__).resolve().parents[1]
DRAWABLES = ROOT / "feature/components/src/main/res/drawable"
LICENSE = ROOT / "feature/components/src/main/assets/licenses/material-symbols-LICENSE"
CACHE = Path(gettempdir()) / "tianyan-material-symbols" / REVISION

# Resource names remain stable; the selected glyph is independent of its calling feature.
ICONS = {
    "admin": "admin_panel_settings", "alarm": "alarm", "alert": "warning",
    "arrowup": "arrow_upward", "attach": "attach_file", "back": "arrow_back",
    "battery": "battery_full", "bookmark": "bookmark", "bookopen": "menu_book",
    "bot": "smart_toy", "brain": "neurology", "bug": "bug_report", "cable": "cable",
    "chat": "chat_bubble", "check": "check", "checklist": "checklist",
    "chevrondown": "keyboard_arrow_down", "chevronright": "chevron_right",
    "chevronup": "keyboard_arrow_up", "close": "close", "code": "code",
    "community": "groups", "compress": "compress", "copy": "content_copy",
    "cpu": "memory", "document": "description", "download": "download",
    "edit": "edit", "extension": "extension", "file": "draft",
    "folder": "folder", "folderdownload": "drive_folder_upload", "folderopen": "folder_open",
    "fontsize": "format_size", "gitbranch": "fork_right", "gitcommit": "commit",
    "globe": "language", "hammer": "hardware", "history": "history", "home": "home",
    "hub": "network_node", "image": "image", "info": "info", "key": "key",
    "link": "link", "list": "format_list_bulleted", "logs": "article", "mail": "mail",
    "model": "auto_awesome", "more": "more_horiz", "mount": "hard_drive",
    "nav_dashboard": "speed", "nav_message": "chat_bubble",
    "nav_repository": "folder_open", "nav_settings": "settings",
    "network": "network_node", "openinnew": "open_in_new", "package": "package_2",
    "palette": "palette", "phone": "smartphone", "play": "play_arrow",
    "plus": "add", "powersettingsnew": "power_settings_new", "prompt": "text_fields",
    "refresh": "refresh", "reverse": "undo", "save": "save", "sdcard": "sd_card",
    "search": "search", "server": "dns", "settings": "settings", "shield": "shield",
    "sparkles": "auto_awesome", "speed": "speed", "sponsor": "favorite",
    "stickynote": "sticky_note_2", "stop": "stop", "storage": "storage",
    "terminal": "terminal", "trash": "delete", "tune": "tune", "update": "system_update",
    "vibrate": "vibration", "visibility": "visibility", "visibilityoff": "visibility_off",
    "volume_off": "volume_off", "volume_on": "volume_up", "workspace": "folder_open",
    "wrench": "build", "menu": "menu", "sessions": "forum",
    "panel_left_close": "left_panel_close", "panel_right_open": "right_panel_open",
    "write_document": "edit_document",
}
MIRRORED = {
    "back", "chevronright", "arrowup", "list", "logs", "openinnew", "reverse",
    "menu", "panel_left_close", "panel_right_open",
}


def fetch_asset(relative):
    cached = CACHE / relative
    if cached.is_file():
        return cached.read_bytes()
    for attempt in range(3):
        try:
            with urlopen(f"{BASE}/{relative}", timeout=30) as response:
                content = response.read()
            cached.parent.mkdir(parents=True, exist_ok=True)
            cached.write_bytes(content)
            return content
        except (URLError, TimeoutError) as error:
            if attempt == 2 or isinstance(error, HTTPError) and error.code == 404:
                raise RuntimeError(f"Could not fetch {relative}: {error}") from error
            sleep(0.5 * (attempt + 1))


def fetch_icon(entry):
    resource, glyph = entry
    svg = ET.fromstring(fetch_asset(f"symbols/web/{glyph}/materialsymbolsrounded/{glyph}_wght300_24px.svg"))
    viewport = svg.attrib.get("viewBox", f"0 0 {svg.attrib['width']} {svg.attrib['height']}")
    x, y, width, height = map(float, viewport.split())
    if width != height or width not in (24, 960):
        raise ValueError(f"Unexpected viewport for {glyph}: {viewport}")
    paths = list(svg.iter("{http://www.w3.org/2000/svg}path"))
    if not paths or any(path.attrib.get("transform") for path in paths):
        raise ValueError(f"Unsupported SVG structure for {glyph}")
    mirror = '\n    android:autoMirrored="true"' if resource in MIRRORED else ""
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f'<!-- Material Symbols Rounded, weight 300, optical size 24; Apache-2.0. Glyph: {glyph}.',
        f'     Source: google/material-design-icons@{REVISION}; converted from SVG. -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp" android:height="24dp"',
        f'    android:viewportWidth="{width:g}" android:viewportHeight="{height:g}"{mirror}>',
        f'    <group android:translateX="{-x:g}" android:translateY="{-y:g}">',
    ]
    for path in paths:
        lines.append(f'        <path android:fillColor="#FF000000" android:pathData={quoteattr(path.attrib["d"])} />')
    lines += ['    </group>', '</vector>', '']
    return DRAWABLES / f"components_ic_{resource}.xml", "\n".join(lines)


def main():
    # Fetch and validate the entire set before touching any repository resource.
    with ThreadPoolExecutor(max_workers=6) as pool:
        resources = list(pool.map(fetch_icon, ICONS.items()))
    license_text = fetch_asset("LICENSE").decode("utf-8")
    for path, source in resources:
        path.write_text(source, encoding="utf-8", newline="\n")
    LICENSE.parent.mkdir(parents=True, exist_ok=True)
    LICENSE.write_text(license_text, encoding="utf-8", newline="\n")
    print(f"Updated {len(resources)} Rounded vector drawables from {REVISION}.")


if __name__ == "__main__":
    main()
