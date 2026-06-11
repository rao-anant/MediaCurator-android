#!/usr/bin/env python3
"""
Independent duplicate-photo finder.
Run in Termux after: termux-setup-storage
  python find_dupes.py
"""
import os, hashlib
from collections import defaultdict

SCAN_DIRS = [
    '/storage/emulated/0/DCIM',
    '/storage/emulated/0/Pictures',
    '/storage/emulated/0/Movies',
    '/storage/emulated/0/Download',
    '/storage/emulated/0/WhatsApp/Media',
]
EXTENSIONS = {'.jpg', '.jpeg', '.png', '.heic', '.heif',
              '.mp4', '.mov', '.gif', '.webp', '.3gp', '.mkv'}

def md5(path):
    h = hashlib.md5()
    try:
        with open(path, 'rb') as f:
            while chunk := f.read(8192):
                h.update(chunk)
        return h.hexdigest()
    except Exception:
        return None

hashes = defaultdict(list)
total = errors = 0

for d in SCAN_DIRS:
    if not os.path.exists(d):
        continue
    for root, dirs, files in os.walk(d):
            dirs[:] = [x for x in dirs if not x.startswith('.')]  # skip .thumbnails etc
        for fname in files:
            if os.path.splitext(fname)[1].lower() not in EXTENSIONS:
                continue
            path = os.path.join(root, fname)
            total += 1
            if total % 1000 == 0:
                print(f"  scanned {total}…")
            h = md5(path)
            if h:
                hashes[h].append(path)
            else:
                errors += 1

dupes = {k: v for k, v in hashes.items() if len(v) > 1}
reclaimable = sum(
    sum(os.path.getsize(p) for p in paths) - max(os.path.getsize(p) for p in paths)
    for paths in dupes.values()
)

print(f"\n{'='*60}")
print(f"Files scanned : {total:,}")
print(f"Hash errors   : {errors:,}")
print(f"Unique hashes : {len(hashes):,}")
print(f"Dupe groups   : {len(dupes):,}")
print(f"Reclaimable   : {reclaimable / 1_048_576:.1f} MB")
print(f"{'='*60}")

for h, paths in sorted(dupes.items(), key=lambda x: -len(x[1]))[:50]:
    size = os.path.getsize(paths[0])
    print(f"\n[{len(paths)} copies · {size/1024:.0f} KB each]")
    for p in paths:
        print(f"  {p}")
