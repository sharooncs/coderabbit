# HouseMove 2.1 proposal, version 3 (after audit round 2)

Versions 1 and 2 are beside this file (`spec-v1.md`, `spec-v2.md`). Version 3 keeps version 2 except where listed. The rule for this round: when a mechanism added in version 2 brought new risk, prefer dropping or narrowing it over adding more machinery.

## Dropped from version 2
- **The Organiser recovery code** (paper or screen). Recovery is instead: a second Organiser (strongly suggested at setup), the person's own backup file, or Android's own backup if left on.
- **Invite files sent by message.** Joining always happens once face to face (same Wi-Fi, a phone hotspot, or the built-in hotspot QR). Someone who can never meet in person waits for the online service (not in 2.1).
- **"Helper for today" deleting itself.** Replaced by the Organiser's **Done, thanks Sam**, which removes Sam from the move only after his phone has nothing unsynced; his phone then removes its copy.
- **Serving the APK over the hotspot.** Replaced by **Share the app** (the phone's own Quick Share, works with no network) and a download QR when there is internet.
- **"Right / Undo" for Helpers.** Kept for Organisers and Deciders, and moved before any data is sent. For Helpers, a list of who joined, each with Remove.
- **Keeping contents lines from both copies** in a merge.

## Changed from version 2
1. **Open grants.** Grants with no appointer that were accepted before a fixed cutoff (the 2.1 release) keep counting exactly as in 2.0; after it, only the founder's does. Legitimate 2.0 Organisers (a partner let in by plain code, a phone upgraded from 1.3) keep their role.
2. **Move ID for 2.0 moves.** Derived from the founder; when two phones of a move derive different IDs (one last synced before an earlier person arrived), they fall back once to the 2.0 same-move check and keep the other ID as an alias.
3. **Phone ID** lives in storage that Android's own backup does not copy, so a restored or cloned phone gets a new one. Adding a device never retires another; retiring is explicit ("Retire Sam's old phone"), and a retired phone's unsynced work is accepted once.
4. **Letters and label numbers** come from a phone number the admitting Organiser's phone assigns at join (letter = first free in the move and not used by this phone in its other moves; printed numbers from that phone's own range). Letters and ranges of retired phones are never reused. Old untagged labels found in two moves on one phone: ask which move.
5. **Joining.** The invite QR shows "Join Maple Road move as Priya, Decider?" with Yes / Not me before anything is sent. A phone already in a move only joins through a host it knows as an Organiser of that move. "Bring your 23 things" defaults to No, is never offered for "just listing" or storage moves, and archives the old card instead of removing it. A Helper code refuses removed or retired phones, and names stay unique.
6. **Updates by message (after joining).** "Send update" is encrypted to the receiving phone's key, exchanged when it joined face to face; forwarding it to anyone else is useless to them; opening an old update twice is harmless (merges are repeatable). "Open update" is a button on the move card, because messaging apps often hide "Open with". An update from a removed person can be taken in by an Organiser as a restore-merge.
7. **Restore.** The phone becomes the person whose phone made the backup, if that person is in it and not removed; otherwise a Helper when the move has an Organiser. A phone already in the move merges the backup and stays itself. Helpers cannot make backups. Backup files are private: "keep this file to yourself; it lets a phone become you."
8. **Demotion to Helper** removes hidden data from the phone only after a completed sync with an Organiser or Decider phone (so nothing made before the demotion is lost). Backups already saved elsewhere cannot be recalled; say so.
9. **Hidden from Helpers** (value, price, serial, make, model, warranty) moves to a separate record kind Helpers are not sent, so a Helper's copy can never blank those fields on other phones. A Helper handing over a sold item sees only "Paid" or "Collect £40" for that item. Disassembly and cable photos go to Helpers at full size.
10. **Merges keep progress.** Stage and trip are merged together as one stamped part (so undo and "back from storage" work); damage stays until an Organiser clears it, with a stamp; contents lines become separate records with deletions, so a removed line never comes back and nothing is counted twice.
11. **Deleting a move** is allowed while nobody else has ever joined it (whether or not an invite was shown).
12. **Different versions** during a move: the message offers Share the app.
13. **Refused changes at a merge** become an item on Organisers' Home ("Sam's phone tried to mark the sofa sold; Sam is a Helper") without showing cost contents to anyone not allowed them.
14. **Built-in hotspot QR**: kept as an option, explained before its permission prompt (location on Android 8 to 12, "Nearby devices" on 13 and later), with "Same Wi-Fi or a phone hotspot" one tap away; switched off on phones where it fails.
15. **Device names** in "Joined: Sam, on Sam's phone" use the name set in the phone's settings, not a model code.
16. **Developer verification.** Android now requires apps to come from a verified developer in Brazil, Indonesia, Singapore and Thailand (since 30 September 2026), worldwide from 2027. Register the signing key with Google before then; until then, sideloading elsewhere works as now.

## 2.0.1 (ship first, no data-format change)
1. Restore: the phone becomes the backup's own person if present and not removed; otherwise a Helper when the move has an Organiser. `Roles` unchanged.
2. Join: when the phone is already in a shared move, it joins only through a host it knows as an Organiser of that move; otherwise "This phone is already in Elm Street move. HouseMove 2.0 holds one move per phone."
Everything else waits for 2.1, because it changes how phones merge and so needs the data-format version to go up.

## Still open (owner's choices)
- Storage as a destination inside a move ("bring back from storage"), instead of a separate move: the real-life reviewer wants it in 2.1.
- Android's own backup on or off.
- An online service later, for people who never meet and for live sync.
