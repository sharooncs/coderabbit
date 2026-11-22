# HouseMove 2.1 proposal, version 4 (after audit round 3)

Version 3 is beside this file (`spec-v3.md`). Version 4 keeps version 3 except as listed.

## Recovery for a lone Organiser
- While a move has one Organiser and anyone else, the Organiser's phone makes a **small backup without photos** (a few MB) every day to a place picked once (a Drive folder, the phone's Documents, or a USB stick), through Android's own folder picker; it uploads whenever there is a connection. Home nudges when the last one is over a week old. Photos come back from the other phones at the next sync.
- Android's own backup copies only this small data, never photos (backup rules added), and never the phone ID file.
- A few days before move day, Home asks again: "Add a second Organiser?"

## Open grants from 2.0
An open grant (no appointer) counts if it was made before a cutoff time set well after 2.1's release (2.0's end of life, when phones on 2.0 can no longer reach a 2.1 move anyway because the data-format version differs). After the cutoff, only the founder's open grant counts. Residual risk, stated plainly: an app deliberately modified to backdate a grant is not caught; there are no signatures.

## Joining
- 2.1: the "only through a host it knows as an Organiser" check applies when the phone **already holds a card for the move being joined**. Joining a different move opens a new card.
- Day Helpers: their copy of the move expires a few days after its last sync, once nothing on it is unsynced (or at once on "Done, thanks Sam").

## Updates by message
Each update is encrypted to every Organiser and Decider phone the sender knows, so any of them can open it, including after one phone is replaced.

## Family who never visit
**Ask for decisions:** sends a picture list of undecided items (photo, name, room) through any messaging app; the Organiser taps the answers into Triage. No joining, no server.

## Data details
1. Price and sale move into the record Helpers are not sent with their own stamped part. At upgrade the old value is checked against its old stamp and re-sealed with the same author and time, so no decision or sale resets.
2. Letters, code counters and label ranges are handed out from blocks owned by the admitting phone (as handwritten-code prefixes already are), and kept beside the phone ID outside Android's backup, so two Organisers admitting offline, or a restored older backup, never give two phones the same letter or range.
3. A retired phone is accepted once, one way: its work is taken in and nothing is sent back to it.
4. Damage reports become separate records, each cleared on its own, so clearing one never erases a report the clearer has not seen.
5. A Helper's "handed over" (collected by the buyer or charity) is its own part that Helpers may set; the amount to collect is sent only to the Helper assigned to that hand-over.

## Storage, minimal, in 2.1
One "Storage unit" place with no rooms; trips can go there; a later "Bring back from storage" trip to the new home. Destinations named after a person wait.

## 2.0.1, corrected
1. **Restore.** The app's restore is a merge. When the phone is alone, it first drops its own self-made person, then becomes the person whose phone made the backup, or a Helper if that person is missing or removed. A phone whose own person is in the backup stays itself. When merging into a shared move, unknown people whose only grants are open are not taken in.
2. **Join.** In the joining phone's claim branch, before anything is sent: the host must be a known member whose role is Organiser. Refusal message: "This phone is already in Elm Street move, and HouseMove 2.0 holds one move per phone. If this is the same move, sync with an Organiser first."
Ship both together.
