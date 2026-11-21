# HouseMove

An Android app for running a self-move systematically: catalogue, decide,
pack, load, unload, unpack. It works fully offline. There is no account and no
server; phones share data by syncing directly with each other.

## Install

Build the APK (below) or download it from the "HouseMove APK" workflow run in
GitHub Actions, copy it to the phone and open it. Android will ask to allow
installing from that source. Needs Android 8.0 or newer.

Every build is signed with the key in `keystore/`, so a newer APK installs over
an older one and keeps its data.

## How the move runs

| Stage | In the app |
| --- | --- |
| Set up | Move date, both homes' rooms, a colour per new-home room |
| Catalogue | Walk-through: one photo per big thing, name it later |
| Decide | Triage: Move, Sell, Donate, Bin or Store |
| Plan | A destination room for every item; door-width fit check; load estimate |
| Prepare | Timeline of tasks counted back from move day; supplies and costs |
| Label | Print a sheet of QR labels, or write the short code the app gives you |
| Pack | Scan a label, photograph the open box, say what's inside, seal |
| Move day | Record both homes, scan onto the van, final sweep, scan off the van |
| Reconcile | Anything loaded but not delivered is listed before the van goes back |
| Unpack | Boxes in priority order; search finds which box anything is in |
| Close out | Inventory PDF/CSV, damage report with before and after photos |

Furniture, appliances and valuables are catalogued one by one. Everything
small is catalogued by box, at the moment it is packed.

## Several phones

Each phone holds a full copy. To sync, one phone shows a code
(More → Sync and backup → Show sync code) and the other scans it. Both must be
on the same Wi-Fi, or one on the other's hotspot. Data and photos go both ways;
if the same record was changed on both, the most recent change wins.

- Set the move up on one phone only. Other phones join by syncing.
- Give each phone its own letter in Settings; handwritten box codes start with it.
- Sync whenever the phones are together, and the evening before move day.
- The sync connection is not encrypted, so use a network you trust.

A backup file (zip of data and photos) can be saved or shared from the same
screen, and merged into any phone.

## Build

Requires JDK 17 and the Android SDK (platform 35).

```
./gradlew :app:testDebugUnitTest          # simulated Android on the JVM, about 2 minutes
./gradlew :app:assembleRelease            # app/build/outputs/apk/release/app-release.apk
./gradlew :app:connectedDebugAndroidTest  # on a connected phone or running emulator
```

## How it is tested

Three layers, all free to run:

1. **Logic and edge cases** (`DataTest`, `EdgeCaseTest`). Merge rules, phones with
   clocks that disagree, the same box edited on two phones, delete against edit,
   duplicate labels, a photo deleted on one phone, a transfer cut off half way,
   a stranger connecting to the sync port, damaged or newer-version data files,
   files that are not backups, changing the move date, deleting a room that is in
   use, and a 600-box move staying fast.
2. **Every screen and flow on simulated Android** (`ScreensTest`, `FlowsTest`,
   `JourneyTest`, `PdfTest`). Each screen opens with data, with none, and on a
   small dark phone with enlarged text. Flows are driven by tapping and typing:
   setup, joining as a second phone, scanning known, unknown and wrong codes,
   packing, relabelling, undo, damage, reconcile, erase. Every printed label on
   every sheet layout is scanned back.
3. **The real app on a real Android emulator** (`DeviceTest`, run by GitHub
   Actions on Android 8.0 and 14). The whole journey from a fresh install, a photo
   taken through the camera, the scanner running on live camera frames, PDFs
   opened with Android's own renderer and their labels scanned, sync over a real
   socket, backup through the share route, and the app being rebuilt and stopped.
   Screenshots from the run are attached to the workflow as artifacts.

What no simulation covers: a physical camera reading a printed sticker in real
light, a physical printer's scaling, and two phones on one particular Wi-Fi
network (some routers and guest networks stop devices talking to each other; a
phone hotspot avoids that).

## Code map

- `data/` – model, JSON store, merge, backup, move logic (fit check, volume, search)
- `sync/` – phone-to-phone sync over a local socket
- `pdf/` – QR label sheets, door signs, inventory and damage reports
- `ui/` – Compose screens; `Camera.kt` holds photo capture and the QR scanner
