#!/usr/bin/env python3
"""
Rename test photos into random dates across 2000, 2001, 2002.

Why: Media Curator resolves an image's date from its FILENAME first
(regex (\\d{4})[_-]?(\\d{2})[_-]?(\\d{2}) -> YYYY-MM-DD), above EXIF/metadata.
So renaming a file to e.g. IMG_2001-07-14_0042.jpg makes the app file it under
July 2001 -- no EXIF editing needed. Great for screenshots without real photos.

Usage:
    python rename_testphotos.py            # operates on the current directory
    python rename_testphotos.py "C:/path"  # or a directory you pass in

It renames ALL image files in that directory (jpg/jpeg/png/webp/gif/bmp/heic).
Two-phase rename (temp names first) avoids any name collisions. The script never
renames itself. It also sets each file's modified-time to the chosen date.
"""

import os
import sys
import random
import calendar
from datetime import datetime

YEARS = [2000, 2001, 2002]
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif"}


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else os.getcwd()
    target = os.path.abspath(target)
    script_name = os.path.basename(os.path.abspath(__file__))

    files = [
        f for f in sorted(os.listdir(target))
        if os.path.isfile(os.path.join(target, f))
        and f != script_name
        and not f.startswith(".")
        and os.path.splitext(f)[1].lower() in IMAGE_EXTS
    ]

    if not files:
        print(f"No image files found in: {target}")
        return

    random.shuffle(files)  # so years/months aren't correlated with original order

    # Phase 1: move everything to unique temporary names so target names can't
    # collide with an existing (not-yet-renamed) file.
    temp_map = []  # (temp_path, original_ext)
    for i, name in enumerate(files):
        ext = os.path.splitext(name)[1].lower()
        temp = os.path.join(target, f"__tmp_rename_{i}{ext}")
        os.rename(os.path.join(target, name), temp)
        temp_map.append((temp, ext))

    # Phase 2: assign a random year/month/day and rename to the final name.
    per_year = {y: 0 for y in YEARS}
    for seq, (temp, ext) in enumerate(temp_map, start=1):
        year = random.choice(YEARS)
        month = random.randint(1, 12)
        day = random.randint(1, calendar.monthrange(year, month)[1])  # valid day
        per_year[year] += 1

        final_name = f"IMG_{year}-{month:02d}-{day:02d}_{seq:04d}{ext}"
        final_path = os.path.join(target, final_name)
        os.rename(temp, final_path)

        # Bonus: set the file's modified-time to match (noon on that day).
        ts = datetime(year, month, day, 12, 0, 0).timestamp()
        os.utime(final_path, (ts, ts))

    print(f"Renamed {len(temp_map)} files in: {target}")
    for y in YEARS:
        print(f"  {y}: {per_year[y]} photos")


if __name__ == "__main__":
    main()
