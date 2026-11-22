# HouseMove 2.1 proposal, version 2 (after audit round 1)

Version 1 is in `spec-v1.md` beside this file. This version keeps its aims and answers the round 1 findings. Items marked [fix in 2.0.1] are holes found in today's app that should be fixed whether or not 2.1 goes ahead.

## 0. Principles
1. One app. The role shapes Home and the More menu; the dock is the same for everyone, so moving between roles or moves never surprises.
2. A phone can hold several moves, one card per move. Every move has a move ID; every installed copy of the app has a phone ID.
3. Data travels three ways without a server: face to face (a QR that carries its own hotspot), update files sent through any messaging app, and backups.
4. Nothing is lost or mixed silently: moves never merge by accident, removals check for unsynced work, a change refused at a merge becomes a question for an Organiser.

## A. First open and "Your moves"
**A1. First open.** Two large buttons: **Start a new move** and **Join a move**. Under Join, a small link: **Lost your phone?** (restore, see A7).

**A2. Later opens.** The move used last opens. Its name at the top carries a role chip ("Maple Road · Helper") and a dot when another move on the phone has something waiting. Tapping it opens **Your moves**: one card per move (name, your role, days to go, progress, last synced); start or join another; per card: back up, archive, remove (rules in A8). Finished moves fold under "Earlier moves".

**A3. Start a new move (you become its founder and Organiser).**
1. What is it for: four picture tiles. A line: "Has someone already started this move? Join theirs instead."
2. The move: name pre-filled, date optional, your name optional (spoken or typed).
3. Rooms in the old home: tap tiles or skip. 4. Rooms in the new home: "Same as old home" or skip (skipped automatically for "just listing").
5. The move is saved here (set-up is complete), then optional **Who's helping?**:
   - First suggestion: "Add a second Organiser, so the move never depends on one phone."
   - Add people: say or type a name, tap a role, repeat.
   - Organisers and Deciders each get their own invite. Helpers can share one **Helper code** (valid 10 minutes, several phones; each phone gives the name it remembers).
   - Show invites one at a time. When a phone joins, the Organiser sees "Joined: Sam, on a Galaxy A12" with **Right** / **Undo**; the next invite appears only after Right.
   - Printed: an **Organiser recovery code** (a few words) on the Organiser's sheet, for A7.
   - "Do this later" goes to Home; People opens the same screen any time.
For one-person kinds of use (just listing; storage with nobody else), people, approvals and Who's helping stay out of sight until a second person is added.

**A4. Join a move.**
1. The camera opens at once. The invite QR carries the move's name and ID, the person's name and role, and how to reach the showing phone (its own local-only hotspot, so no Wi-Fi or data is needed).
2. Before anything is sent: "Join **Maple Road move** as **Priya, Decider**?" **Yes** / **Not me**. Not me gives the invite back unused.
3. If this phone has a move of its own with nobody else in it and things in it: "Bring your 23 things into Maple Road?" (2.0 already does this for a phone that was alone). The empty card is then removed.
4. A phone without the app scans the same QR with its camera and gets the APK from the showing phone (sideloaded, no store), then scans again.
5. Welcome is a banner on Home, not a separate screen. Helpers receive lists and thumbnails first; full photos follow when needed.

**A5. Adding people later.** People reuses Who's helping. "New phone for Sam" retires Sam's old phone ID, so the old phone is refused if it turns up.

**A6. Moves, phones and labels.**
- **Move ID:** random, made once by the founding phone, never changed, kept outside the merge, and checked in every QR, sync hello, backup and update file before any data moves. A different ID is refused with a plain message. For moves made with 2.0, every phone derives the same ID from the founder (the person with the earliest open grant, held by every phone of the move). The data layout version goes up, so a 2.0 phone gets the existing "different versions" message instead of mixing.
- **One card per move ID:** joining, restoring or opening an update file for a move already on the phone goes into that card.
- **Phone ID:** each installed copy has one; a person's record lists their phones. Handwritten-code letters belong to the phone, not the person, so two phones of one person never write the same code.
- **Labels:** new printed QR labels carry a short move tag and a small printed mark; scanning another move's label offers to switch to that move. Printed numbers come from a range given to the printing phone, so two phones printing offline never make the same number.
- **Per move:** person, phone letter, code counter, current room and trip, last sync, last backup, photos folder, label range. **Per phone:** phone ID, your name, book-lookup choice.

**A7. Organisers and recovery.**
- Only the founder's open grant counts. Every other Organiser needs a grant from an Organiser. [fix in 2.0.1: today a restored backup makes a new open-grant Organiser that every phone accepts]
- Restoring a backup never asks who you are: the phone becomes the person whose phone made the backup. If that person still has another working phone, the restore counts as "New phone for them" and needs an Organiser to accept it; if the person is the only Organiser, the recovery code accepts it.
- **Lost your phone?** on a new phone: scan any member's code and give the Organiser recovery code; the move comes from that member's phone and you are its Organiser again. No backup needed (what a Helper's phone lacks arrives at the next sync with an Organiser or Decider).
- An Organiser is asked to confirm before stepping down; the last Organiser cannot.

**A8. Removing and deleting.**
- **Delete a move:** only while you are the only person and no invite was ever shown.
- **Remove from this phone:** first lists changes and photos no other phone has, and offers Sync, Send update or Back up. The last Organiser cannot remove a shared move until another Organiser exists.
- **Removed from a move:** the card says so, offers to send your unsynced work to an Organiser as an update file, then removes itself.
- A join cut off part-way: the joining phone counts as joined as soon as the data is through (photos can follow), so the Organiser never sees "nobody" while the phone holds the move.

**A9. Without Wi-Fi.**
- Face to face: the QR's built-in hotspot (Android 8 and later) connects two phones anywhere, no internet.
- **Send update:** from a card, through the phone's share sheet (Quick Share offline, WhatsApp or email anywhere). It holds what the receiver's role may see, small photos only. Opened in HouseMove it is merged with exactly the same rules and checks as a sync. This is how a Decider in another city works.
- **Invite by message:** an invite file for one person plus a short code said over the phone; usable once, for 24 hours.

**A10. Merges keep progress.** [fix in 2.0.1] Today two phones editing one box offline keep only the newer whole record, so a reported damage or an unpacked tick can vanish. Instead: the stage keeps the furthest along (only an explicit undo goes back), damage once reported stays until an Organiser clears it, and contents lines from both are kept.

## B. One app, shaped by role
- **Dock for everyone:** Home, Items, Boxes, Move day, More.
- **Home by role:**
  - Organiser: approvals waiting, people, the six phases.
  - Decider: items waiting for a decision, what happened to their suggestions, costs to suggest.
  - Helper: what is physically next for things (pack, load, unload, unpack, sweep, meter photos), driven by where things are, not by the date; jobs given to them today; "Ask a question".
  - Everyone: Sync on Home; the role chip; a banner when the role changes ("You are now a Decider").
- **Helpers can:** pack, scan, load, unload, unpack, sweep, take meter and condition photos, report damage, find things, ask for a decision, tick jobs given to them, and mark an approved sell/donate/bin item "collected".
- **Helpers are not sent** [fix in 2.0.1]: costs and cost suggestions, receipts, item values and prices, serial numbers, make and model, warranty, serial-label photos. Find does not list what is marked "travels with me".
- **Helper for today:** an invite option for movers and day helpers; at the end of the day their phone deletes its copy of the move.
- **Role changes:** becoming a Helper removes the hidden data from the phone and its backups at the next sync [fix in 2.0.1]; a promoted person sees "Costs and decisions arrive at your next sync with an Organiser or Decider" until they have.
- **Printed:** the full sheet for Organisers; a half-page Helper card that also works without the app (door-sign colours; "write KIT on two sides").

## C. Bigger ideas raised, not in 2.1 unless chosen
- Several destinations in one move (a storage unit, a named person, "bring back from storage") instead of extra moves.
- "Start from what you own": a new move copies an earlier move's list.
- Live sync from anywhere (needs an online service and an account).
