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
./gradlew :app:testDebugUnitTest   # data, sync and screen tests
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

## Code map

- `data/` – model, JSON store, merge, backup, move logic (fit check, volume, search)
- `sync/` – phone-to-phone sync over a local socket
- `pdf/` – QR label sheets, door signs, inventory and damage reports
- `ui/` – Compose screens; `Camera.kt` holds photo capture and the QR scanner
