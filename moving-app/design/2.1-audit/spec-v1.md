# HouseMove 2.1 proposal, version 1: first run, several moves per phone, one app per role

This is a proposal for the next version of HouseMove, an Android app for running a house move. It is not built yet. The current app is version 2.0; its code is in `moving-app/` (read it to see what exists today).

## What 2.0 does today (facts)
- One move per phone: all data lives in one folder (`Store(filesDir)`, created in `App.kt`); `DeviceInfo` holds per-phone settings including who this phone is in the move (`personId`), its letter for handwritten box codes, and a counter.
- First open goes straight into a setup wizard (`SetupScreen.kt`): what is it for (self move, movers, storage, just listing), the move (name, date, home names, your name), rooms in the old home, rooms in the new home. Joining someone else's move is a fold on the first page ("Second phone for the same move?") that scans an invite.
- Roles: Organiser, Decider, Helper. Organisers add people (name + role) and show a one-time invite QR (valid 10 minutes); the joining phone scans it, syncs, and becomes that person. Deciders suggest decisions/prices/costs/room plans/tasks; any one Organiser approves or declines; then the Decider may carry it out. Helpers photograph, pack, scan, unpack, report damage, ask for decisions. A move with one person, or no Organiser, is unrestricted.
- Rules are enforced in the Store and again at every merge. Helpers' phones are never sent costs, cost suggestions, receipts or serial-label photos.
- Sync is phone-to-phone over the local network (same Wi-Fi or a hotspot), no server. A plain sync code works only while shown; once a move has more than one person, a new phone needs an invite.
- A move has no ID of its own; when a backup is restored, the app judges whether it is the same move by comparing contents.
- Deleting: an Organiser may delete anything; others only what they added and nobody has touched since, otherwise they ask.

## Proposal A: first run and several moves per phone

### A1. First open: "Your moves"
The lit house, then two large buttons: **Start a new move** ("You organise it and can invite others") and **Join a move** ("Someone is showing you a code"). A small link: **Restore from a backup**.

### A2. Every later open
Opens straight into the move used last. Tapping the move's name at the top opens **Your moves**: one card per move (name, your role in it, days to go, progress ring, last synced); start, join or restore another; per card: back up, archive, remove from this phone. Finished moves fold under "Earlier moves".

### A3. Start a new move (you become its Organiser)
1. What is it for: four picture tiles. A line: "Has someone already started this move? Join theirs instead." (Two separate moves never merge.)
2. The move: name pre-filled, date optional, your name optional (can be spoken).
3. Rooms in the old home: tap tiles, or skip.
4. Rooms in the new home: "Same as old home" in one tap, or skip.
5. Who's helping? (optional): one line each on the three roles; add people by saying or typing a name and tapping a role, repeat; then **Show invites** shows one person's code at a time full screen with their name and role; when that person scans, the card says "Sam joined ✓" and the next appears; anyone can be skipped. **Do this later** goes to Home; this screen is reachable later from People.

### A4. Join a move
1. Camera opens immediately ("On the organiser's phone: People → your name → Show invite"); typing the code is behind a chip.
2. Phones sync, with progress.
3. Welcome card: "You are Sam, Helper on Maple Road move, organised by Asha", the three things the role does; name comes from the invite, correct it if wrong.
4. That role's Home.

### A5. Adding people later (Organisers)
People opens the same "Who's helping?" screen: add list, invite queue, ticks as people join, "New phone for Sam" for a replaced phone.

### A6. Rules
- Several moves per phone, each with its own items, people, labels, backups. Role is per move.
- Your name is remembered per phone and offered for each new move; your letter for handwritten codes stays per move.
- Each move gets its own hidden ID; syncs, invites and backups check it so two moves never mix.
- Invites need both phones on the same Wi-Fi or a hotspot from one phone (remote invites need an online service, not in 2.1).
- The existing move on the phone becomes the first card automatically.

### A7. Edge cases already considered
Started a new move by mistake: delete it from its card before anyone joins. Invite expired or used: "Ask Asha for a fresh code", camera stays open. Different networks: say so, suggest a hotspot. Organiser's phone lost: another Organiser carries on; with one Organiser, restoring a backup on a new phone asks which Organiser you are. Removed from a move: the card says so and offers removal. Two devices for one person: "New phone for this person".

## Proposal B: one app, three experiences (instead of three separate apps)
The invite sets the role; the role shapes the whole app.

| | Organiser | Decider | Helper |
|---|---|---|---|
| Home leads with | Approvals waiting, people, the six phases | Items waiting for a decision, and what happened to their suggestions | Today's jobs and one big "Pack a box" (on move day "Load" or "Unload") |
| Dock | Home, Items, Boxes, Move day, More | Same, minus people and settings | Four: Home, Pack, Scan, Find |
| Sees | Everything | Everything except people and settings; costs as suggestions | Jobs, packing, scanning, finding, asking a question; no costs, no decisions |
| First run | Start a new move, setup, Who's helping? | Join a move, welcome card | Join a move, welcome card |
| Printed sheet | Full 23-step sheet | One-page Decider card | Half-page Helper card, five steps |

Also: anything a role cannot use is removed entirely, not shown greyed; a short "You are now a Decider" card when a role changes, and the app reshapes itself.

## Kinds of use (from 2.0)
Moving myself (van), movers doing it (truck), putting things in storage, just listing what I own. Parts of the app can be switched off per move.
