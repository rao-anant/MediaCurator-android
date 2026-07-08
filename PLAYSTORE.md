# Play Store Submission Notes — Media Curator

## App identity
| Field | Value |
|---|---|
| App name | Media Curator |
| Application ID | `com.anant.mediacurator` |
| Keystore file | `mediacurator.keystore` (gitignored — keep a backup!) |
| Keystore alias | `mediacurator` |
| Passwords | In `keystore.properties` (gitignored) |
| Privacy policy URL | `https://rao-anant.github.io/MediaCurator-android/privacy-policy.html` |

> ⚠️ The keystore file and passwords are gitignored. Back them up to an external drive or
> cloud storage. Losing the keystore means you can never push an update to this app on Play Store.

---

## GitHub Pages — enable the privacy policy URL
1. Go to https://github.com/rao-anant/MediaCurator-android
2. Settings → Pages → Source: **Deploy from a branch**
3. Branch: `main` · Folder: `/docs`
4. Save → wait ~2 min → verify at the URL above

---

## MANAGE_EXTERNAL_STORAGE declaration
When Play Store asks "why does your app need All Files Access?", paste this:

> Media Curator is a media curation app: it browses and deletes photos, videos, and PDF
> documents. All Files Access is used solely to locate PDF files stored in Downloads and
> Documents folders on the device. Android's granular media permissions
> (READ_MEDIA_IMAGES, READ_MEDIA_VIDEO) cover only photos and videos — no equivalent
> permission exists for PDF or document files on Android 13 and above. No files are
> uploaded, shared, or transmitted. The app has no internet access.
>
> (473 chars — Play Console limit is 500)

Note: Google Files, Samsung My Files, and every mainstream file manager use this same
permission. It is the canonical use case in Google's own policy documentation.

---

## ACCESS_MEDIA_LOCATION (place search, v1.1)
No special Permissions Declaration form is required for this permission (that form is only for
background location, SMS/Call Log, and All-files access). If review asks why the app requests it:

> ACCESS_MEDIA_LOCATION is used only to read a photo's own GPS on-device and match it to a city for
> offline place search. No location data is transmitted or stored off the device — the app has no
> internet access. The permission is read-only; the app never writes to photos.

This stays consistent with Data safety = "no data collected" (on-device-only use is not "collection"
per Google's definition, since nothing is transmitted off the device).

---

## Store listing — Short description (≤ 80 chars)
```
Turbocharge curating your photos, videos and PDFs, and enjoy the time reclaimed.
```

## Store listing — Full description
```
Media Curator — clean up years of photos, videos & PDFs. Private, fast, and surprisingly fun!

Take control of a media library that's grown for years — no cloud, no accounts, no privacy
trade-offs. Everything runs 100% on your phone.

✨ BUILT TO DECLUTTER BETTER AND FASTER — NOT JUST BROWSE
Your gallery is great for browsing memories — pinch, scroll, relive. But it never remembers
what you've already sorted through, so cleaning up means wading past the same items over
and over.

Never scroll past the same thousands of photos again. Media Curator finishes the job:
review a month, mark it hidden, and it steps out of your way — next time you pick up exactly
where you left off. What used to drag across endless interrupted sessions now moves many
times faster.

And it's not just photos and videos — Media Curator brings your audio files and PDFs into
the same timeline, which your gallery app simply doesn't do.

Hidden months are never deleted. They stay fully available in your phone's normal gallery,
and you can unhide them anytime. And even when you do delete, it's never instant or final —
items go to a recoverable Trash first, so nothing is ever lost by accident. Best of all,
hiding keeps reviewed items out of your way, so curation stays fast and even fun.

🗂️ BROWSE YOUR WAY
• Photos, videos, audio, and PDFs grouped by year and month
• One tap to show just photos, videos, audio, or PDFs
• Sort by newest, oldest, or largest first
• "Largest first" surfaces your biggest files instantly

✅ CURATE WITH CONFIDENCE
• Full-screen swipe-through viewer
• Long-press to multi-select, then delete, share, or move in one go
• Selection size shown before you confirm — no surprises
• Mark months done and watch your progress add up

♻️ DELETE WITHOUT FEAR
• Deletes go to a recoverable Trash, not gone forever
• Undo the last delete instantly, or restore anything from Trash
• Items auto-clear from Trash after 30 days, or empty it yourself anytime

🧹 RECLAIM SPACE
• File size on every thumbnail; duration on videos and audio
• Sort by largest to see exactly what's eating your storage
• Spot exact duplicate photos and videos, review them side by side, and reclaim the space

🔍 SEARCH
• Search by filename, with fuzzy matching that forgives typos ("flwoer" still finds "flower")
• Search inside your PDFs — find a document by a word on one of its pages
• Search by place — find photos by the city, region, or country where you took them, or browse
  them by location. Share, rename, move, or delete right from the results, or open a photo in your
  phone's gallery. Works fully offline; your locations never leave your phone

📄 PDF SUPPORT
• Browse, view, and delete PDFs alongside your media
• Searchable by content, not just filename

🔒 PRIVACY FIRST
• No internet access — ever. No accounts, no sign-in
• No ads, no analytics, no third-party tracking or crash-reporting SDKs
• Your media never leaves your device
• The app writes only small local helper files (your reviewed-months list, and the PDF,
  duplicate, and place search indexes) — never your photos or any personal data

🔑 PERMISSIONS
• Photos / Videos / Audio: to display your library
• All files access (Android 11+): to find and manage PDF files in Downloads, Documents,
  and other folders
• Photo location (optional): reads each photo's own location on your device to tag it with a
  city for place search — no location ever leaves your phone

Perfect for anyone who wants to periodically clean up their camera roll, reclaim storage,
and methodically work through years of media — without ever handing their photos to a
third party.
```

---

## What's new (release notes) — v1.1
```
• Search and browse your photos by place — the city, region, and country where you took them.
• Act on results right there: share, rename, move, or delete, or open a photo in your phone's gallery.
• Faster place browsing, and your place index is kept across reinstalls — no waiting to re-scan.
• Fully offline, like everything else — nothing leaves your device.
```

---

## Content rating questionnaire
Answer **No** to every question:
- Violence? No
- Sexual content? No
- User-generated content / social features? No
- Shares location? No
- Targets children under 13? No

→ Final rating: **Everyone**

---

## Data safety section
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all user data encrypted in transit? | N/A (no network) |
| Do users have a way to request data deletion? | **No** (no data collected) |

---

## Store assets checklist
- [ ] App icon — 512×512 PNG, no alpha, no rounded corners (Play Console adds them)
- [ ] Feature graphic — 1024×500 PNG or JPG
- [ ] Phone screenshots — minimum 2, at least 320 px wide
- [ ] Short description ✓ (above)
- [ ] Full description ✓ (above)
- [ ] Privacy policy URL ✓ (above — but must be live on GitHub Pages first)
- [ ] MANAGE_EXTERNAL_STORAGE declaration ✓ (above)

---

## Release build steps
1. Fill in `keystore.properties` with real passwords (if still using placeholder)
2. In Android Studio: Build → Generate Signed Bundle/APK → **Android App Bundle (.aab)**
3. Use the `release` build type
4. Upload the `.aab` to Play Console → Production (or Internal Testing first)
