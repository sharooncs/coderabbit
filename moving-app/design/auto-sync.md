# Syncing by itself on the same Wi-Fi (HouseMove 2.3)

**From 2.4** the engine, the timings, the status line and the platforms' discovery below stay, but what is advertised
and how phones trust each other changed: each phone advertises a tag of its own (`HN-…`, TXT `v=2`), the handshake is
Noise IK with the phones' own keys and the sync runs encrypted, and the Wi-Fi keys, pair keys, meetings and HMAUTO1 of
sections 1 and 2 are gone (a 2.4 phone deletes `ring.json`). See `design/encryption.md`, Part 2 and "Part 2 as built".
What follows is 2.3 as it was built.

Today two phones sync when one shows a code and the other scans it. That stays, but it should no longer be the
everyday way. From 2.3, once a phone has joined a move (with the invite QR code, as today), the phones of that move
find each other on the Wi-Fi and sync by themselves while HouseMove is open. Nobody shows or scans a code for that.

This is the design. It is built on Android (`:app`, `:shared`) and on the iPhone (`:sharedUi`, `:shared` iosMain,
`iosApp`'s Info.plist). A later step syncs over the internet through an end-to-end-sealed mailbox on the owner's
always-on Windows laptop; it is not built now, and the last section says what this design leaves ready for it. (It is
built in 2.5, beside this, with the phones' own keys: `design/encryption.md`, "Mailbox as built".)

## In short

- Each phone advertises one Bonjour service, `_housemove._tcp`, while HouseMove is open and "Sync by itself on Wi-Fi"
  is on. Its name holds an opaque tag that changes every hour and that only phones of the same move can work out.
  Nothing else is advertised: no names, no move, no phone ID.
- Two phones of the same move that see each other's tag connect and prove to each other, with HMAC-SHA256 over fresh
  nonces from both sides and both phone IDs, that they hold a secret of the move. Then they run today's sync on the
  same connection, unchanged: the same messages, the same merge rules, the same checks of who may do what.
- The secret is a **Wi-Fi key** of the move, kept only in the phone's no-backup folder. A phone gets it from the phone
  it joins (or, for phones that joined before 2.3, from the next phone it syncs with by code), in a short exchange on
  the new connection that the code the two phones just used opens. Two phones that have synced by themselves also
  keep a **pair key** of their own, which outlives the Wi-Fi key: when someone is removed, the phones that remain
  change the Wi-Fi key and keep syncing with each other through their pair keys.
- Phones on 2.1.1 and 2.2 never see any of it: they do not advertise and nobody connects to them uninvited. Code sync
  keeps working in every direction, byte for byte as before (the golden fixtures are unchanged).

## 1. Discovery

**DNS-SD over mDNS**, service type `_housemove._tcp` (domain `local.`), the same type the iPhone already declares in
`NSBonjourServices` for its local-network check.

| Platform | Advertise | Browse and resolve |
| --- | --- | --- |
| Android | `NsdManager.registerService` | `NsdManager.discoverServices`, then `resolveService`, one at a time (Android before 14 allows one resolve at once) |
| iPhone | `DNSServiceRegister` (dns_sd, through Kotlin/Native's `platform.darwin`) with the port of HouseMove's own listening socket | `DNSServiceBrowse`, `DNSServiceResolve`, `DNSServiceGetAddrInfo` (IPv4), all on one serial dispatch queue (`DNSServiceSetDispatchQueue`) |
| Tests and demo mode | an in-process registry (`LocalDiscovery`) | the same |

dns_sd is used on the iPhone rather than `NWListener`/`NWBrowser` because the sync itself runs on HouseMove's own POSIX
socket (the one code sync already uses): `DNSServiceRegister` can advertise any port, while `NWListener` advertises
only the port of a listener it owns.

**What is advertised.** One service per phone, for the move in use:

- instance name `HM-<tag>-<random>`, for example `HM-3f9a0c1d2e4b5a69-k7qz`;
- TXT record `v=1` (the protocol version) and nothing else;
- the port of the phone's listening socket for syncing by itself (a new random port each time HouseMove opens);
- the phone's own mDNS host name, which the system chooses and already announces; HouseMove adds no name of its own.

**The tag.** `tag = hex(HMAC-SHA256(key = "housemove-discovery:" + moveId, "tag:" + hour))[0..16]`, where `hour` is the
number of whole hours since 1970 (UTC). It is the move's, not the phone's: every phone of the move advertises the same
tag in the same hour, so they recognise each other without connecting. A stranger cannot work it out without the move
ID, which is never advertised (it travels only inside a code or invite and inside a sync), and cannot link two hours'
tags, or tell two moves' tags apart, without it. The random part of the name is drawn again whenever the tag changes
and whenever HouseMove opens. A phone accepts the tags of the hour before, this hour and the hour after (clocks
disagree), for every ID the move is known by (a move from before 2.1 can have two: `MoveIdentity.aliases`); it
advertises the tag of the lowest of those IDs, so that two phones that know each other's IDs agree.

The tag only says "this phone is in a move you know". It is not a credential: anyone on the Wi-Fi can read it and
advertise it back. What lets a phone in is the next step.

## 2. Trust without a code

### The secrets

Each move on a phone has a small **key ring**, `ring.json` in the move's no-backup folder (`no_backup/moves/<folder>/`
on Android, `HouseMove-no-backup/moves/<folder>/` on the iPhone, beside the codes and labels that are already kept
there). It is never in a backup, an update file, the synced data or Android's own backup, and it goes when the move
is taken off the phone. It holds:

- **Wi-Fi keys** of the move: 32 random bytes each (secure random), with a random 16-hex-character ID and the list of
  "outsiders" known when it was made (below). Usually one; at most four are kept, the highest IDs.
- **Pair keys**: one per other phone of the move this phone has synced with by itself, 32 bytes, by phone ID.
- The move ID it belongs to. A ring for another move ID (the move was erased, or the phone joined another move into
  the same folder) counts as empty.

A **meeting** is a code sync or an invite join that has just happened (`Meetings`, in memory only, for ten minutes):
both phones saw the same code (the one shown on screen and scanned), and each knows the other's phone ID from that
sync. A meeting is used once, to start syncing by itself.

### The handshake

Phone D dials phone A (the one it found advertising). Strings are `writeUTF`, as in code sync.

```
D → A   "HMAUTO1", tag, D's service name, nD (32 random bytes)
A → D   nA (32 random bytes)
D → A   n, then n proofs: HMAC(k, "HMAUTO1 dialler" | tag | nD | nA | idD) for each key k D holds (n ≤ 64, shuffled)
A → D   "OK" + HMAC(k, "HMAUTO1 answerer" | tag | nD | nA | idD | idA)     or "NO" or "BUSY"
D → A   "GO" + HMAC(k, "HMAUTO1 confirm" | tag | nD | nA | idD | idA)      or "NO"
A → D   "SYNC" or "NO"
then today's sync, unchanged (D says the hello, A answers it), and at its end each phone's offer of Wi-Fi keys:
D → A   ring offer       A → D   ring offer
```

`|` is a length-prefixed concatenation (each part as a four-byte length and its UTF-8 bytes; nonces as raw bytes).
D's service name is the one it advertises (public anyway): it lets A know, once the proofs are through, which advertised
phone it has just synced with, so that A does not dial it again at once.
`idD` and `idA` are phone IDs. They are never sent: each side finds the other's by trying the phone IDs of the move it
knows (a few dozen HMACs at most). So a stranger who connects learns no phone ID, and the proofs are still bound to
both. The keys D proves with are its Wi-Fi keys, its pair keys and its meeting codes; A tries each of its own against
each phone it would accept:

| Key | Accepted for the phone IDs |
| --- | --- |
| a Wi-Fi key | every phone of the move: on someone's list of phones, not retired, its person not removed |
| a pair key | the one phone it is for, if that phone is still such a phone |
| a meeting's code | the one phone of that meeting |

A pair key is preferred to a meeting, and a meeting to a Wi-Fi key. After A's proof, D checks A the same way, and
only then confirms. A checks D's confirmation and says "SYNC".

Then the two phones run `WifiSync`'s own exchange on the same connection, with the code check replaced by the
handshake: D sends the hello (`HMSYNC3`, the word `AUTO` for the code), A answers it, and everything after is as in
a code sync, including who may sync (`admission`), what a Helper is sent, the retired-phone and removed-person
answers, and the merge. Two extra checks: the phone ID in each hello must be the one the handshake proved, and the
person it gives must be that phone's owner in the move. New phones are never let in this way.

### Replay, removed phones, strangers

- **Replay.** Every proof covers both nonces, fresh from secure random on each side for each connection, and its
  direction ("dialler", "answerer", "confirm"). Words recorded from one connection prove nothing on the next, and a
  phone's own proof cannot be sent back to it.
- **A stranger on the same Wi-Fi** holds no key: none of its proofs match, and it is told "NO" before anything of the
  move is sent. It learns that the phone runs HouseMove (the service type says so anyway), never a name or a phone ID.
  A phone syncs by itself with one phone at a time ("BUSY" to more), handles at most four connections at once, and
  gives each step of the handshake five seconds, so a stranger cannot hold it up for long. A phone of another move
  (another tag), or anything that does not begin with `HMAUTO1`, is not answered at all.
- **A phone that left or was removed.** Three things keep it out:
  1. *The move's own rules, at once.* A phone retired by an Organiser, or one whose person was removed, is not a phone
     of the move: no key is accepted for it, and today's sync refuses it as well. A phone that took the move off
     itself has no ring any more.
  2. *The Wi-Fi key changes.* A Wi-Fi key records the outsiders (retired phones and phones of removed people) known
     when it was made. As soon as a phone learns, through any sync, of an outsider its Wi-Fi key does not list, it
     stops using and accepting that key: the removed phone might still hold it and could claim to be someone else
     with it. The phone that next has to offer a key and has none makes a new one, which lists the new outsider, and
     hands it on.
  3. *Pair keys carry on.* A new Wi-Fi key is handed on only to a phone proved with a pair key, a meeting or a Wi-Fi
     key that is still good, never with one that has just turned out to be known to someone removed. A removed phone
     does not know the pair keys of other phones (each pair key is made from a handshake it was not part of), so
     phones that have synced by themselves before keep doing so after a removal, with no code. A phone that never
     synced by itself with any of them needs one code sync.
- **What it does not defend against.** Neither code sync nor this encrypts anything, so someone who can read the
  Wi-Fi's traffic (an open network, or someone with the password capturing packets) can read the move as it syncs,
  and with it the keys. That is today's rule, kept: "use a network you trust". And someone already in the move can
  misuse it while they are in it; the move's rules (who may change what) still hold for them.

### The key ring exchange

At the end of a sync by itself, each phone offers its Wi-Fi keys that are still good (ID, key, outsiders) and takes
in the other's: a key it does not have, which is good in its own view (its outsiders are all listed), joins its ring.
The highest ID is the one in use; up to four are kept, so phones that have not met yet still share one. D offers
first; A takes D's in and, if it still has no good key, makes one; then A offers. So a phone without a key always
leaves with one, and two phones never make one each in the same exchange. Keys are offered only when the sync went
both ways and when the key that proved the other phone is still good after the merge.

Both phones then keep a pair key for each other, `HMAC(k, "HMAUTO1 pair" | tag | nD | nA | lower ID | higher ID)`,
unless the proof was already their pair key.

### Phones that joined before 2.3

They have no ring. The first time such a phone syncs by code with a 2.3 phone (either one showing the code), both
phones note the meeting. Within ten minutes, as soon as they see each other on the Wi-Fi, they run the handshake with
the code they both used as the key, sync, and leave with a Wi-Fi key and a pair key. From then on that phone syncs by
itself with every phone of the move that has the same Wi-Fi key. A phone joining with an invite from a 2.3 phone gets
its keys the same way, at once. If neither phone has a Wi-Fi key yet (the whole family has just updated), the phone
that answers makes one. Nothing new is sent in the code sync itself, which is why its bytes stay as they are.

## 3. When syncs happen

The engine (`AutoSync`, common Kotlin) runs only while HouseMove is open and the setting is on. It works on the move
in use; another move on the phone syncs when it is opened.

| When | What |
| --- | --- |
| HouseMove comes to the front | advertise, browse, and sync with each phone of the move as soon as it is found |
| every two minutes while open | sync again with each phone found (only the phone whose service name sorts first dials; see below) |
| a change made on this phone | five seconds after the last change in a row, sync with every phone found, whoever dials |
| the network changes | the phone's addresses are looked at every few seconds; when they change, advertise again, browse again and sync with everyone found a few seconds later |
| a code sync or invite join has just happened | sync with every phone found at once, to swap keys while the meeting lasts |

**Who dials.** Every phone listens. Of two phones, the one whose service name sorts lower dials for the timed syncs;
the other waits. If it has not been called for five minutes although it sees the other, it dials itself. A change
made on a phone is pushed by that phone, whichever it is.

**One at a time.** A phone runs one sync by itself at a time, either way: a phone that is dialling or answering says
"BUSY" to anyone else, who tries again in 3 to 8 seconds (a random wait, so two phones that dialled each other at
once do not collide again). Code syncs are separate and may run beside it, as two code syncs already may.

**Backing off.** A phone that cannot be reached, or that refuses, is tried again after 15 seconds, then 30, 60 and
so on, at most every 10 minutes, each with a random fifth either way. A meeting, a new key or a network change starts
everyone afresh.

**What the person sees.** The status line says what the last sync by itself did.

## 4. Old phones

- Phones on 2.1.1 and 2.2 do not advertise, so no 2.3 phone ever dials them, and they never receive `HMAUTO1`. The
  listening socket for syncing by itself is a different one from the code-sync socket, and its port is only ever in
  a Bonjour advert.
- Code sync is unchanged in every direction: a 2.3 phone showing its code to a 2.2 phone, or scanning a 2.2 phone's
  code, sends and expects exactly what 2.2 does. The golden fixtures (`sync-join.json`, `sync-plain.json`,
  `sync-refusals.json`, `invites.json`) are unchanged and still pass byte for byte.
- A 2.3 phone notes a meeting with a 2.2 phone like any other; it is never used and lapses after ten minutes.
- Sync and backup's "How syncing works" says when the code is still needed: the first sync after an update, a phone
  with an older HouseMove, and Wi-Fi that keeps devices apart.

## 5. What the person sees

Android, More, Sync and backup, at the top:

- a line, for example **"Up to date with Asha's phone · 2 min ago"**, **"Syncing with Asha's phone…"**,
  **"Looking for the family's phones on this Wi-Fi"**, **"Not on Wi-Fi"** or **"Sync by itself is off"**;
- under it, when it applies, one hint: "Sync once with a code to start syncing by itself." (no key yet), the last
  sync ("Last synced with Asha's phone 3 hours ago."), or, with the switch off, "Phones sync when one shows its code
  and the other scans it.";
- a switch, **"Sync by itself on Wi-Fi"**, on by default (a setting of the phone, for all its moves);
- below, as now, Show sync code and Scan sync code, which stay for everything else: joining, old phones, phones
  whose Wi-Fi keeps devices apart (guest networks), and the first sync after an update.

The iPhone's "Get fresh data" screen shows the same line and switch above its code buttons. The words are made once,
in common code (`AutoSync.Status`), for both.

## 6. Limits of the platforms

- **iPhone.** An app cannot listen for or keep connections while it is in the background. HouseMove stops
  advertising and listening when it leaves the screen and starts again when it comes back (UIKit's become-active and
  enter-background notifications). A sync under way when it leaves may be cut short; it is tried again next time.
- **Android.** HouseMove stops when its screen is no longer shown (the activity's onStop) and starts again when it is
  (onStart). It does not run a background service: Android limits background work, and it would cost battery. A sync
  under way finishes if Android lets it.
- **When the app is closed nothing syncs.** It catches up the next time it is opened, with whichever phones of the
  move are open on the same Wi-Fi then. No promise beyond that.
- **Networks that keep devices apart** (many guest and office networks, some routers' "AP isolation", some mesh
  systems that do not pass mDNS between access points): phones do not see each other. The code still works there
  when the phones can connect at all; a phone's hotspot always works.

## 7. Privacy

**On the local network**, while HouseMove is open and the setting is on: the mDNS advert above (service type,
`HM-<tag>-<random>`, `v=1`, a port and the system's host name), mDNS queries for `_housemove._tcp`, and connections
to the phones that advertise the move's tag. The tag cannot be traced to the move or to anyone without the move's
ID. A sync by itself sends what a code sync sends today, between phones of the move only.

**Android permissions: none new.** `NsdManager` needs no permission beyond INTERNET, which the app has: it is a
system service that does the multicast itself, so no `CHANGE_WIFI_MULTICAST_STATE` and no multicast lock. Android 13's
`NEARBY_WIFI_DEVICES` is for Wi-Fi Direct, Wi-Fi Aware and scanning, which HouseMove does not use. Network changes are
seen by looking at the phone's own addresses (`NetworkInterface`), so not even `ACCESS_NETWORK_STATE` is needed.

**iPhone.** `NSBonjourServices` already lists `_housemove._tcp` (for the local-network check); its comment now says
phones find each other with it. `NSLocalNetworkUsageDescription` now says the app finds the other phones of the move on
the Wi-Fi. iOS asks the local-network question the first time HouseMove browses, which in practice is when it joins
(as today). If the person says Don't Allow, syncing by itself cannot work: the status line says so ("HouseMove may not
use your Wi-Fi") with Open Settings, as the join screens already do. The privacy manifest does not change: dns_sd and
HMAC are not required-reason APIs, and nothing is collected or tracked.

**Crypto and export.** HMAC-SHA256 is written in common Kotlin on top of the platform's SHA-256 (CommonCrypto's
`CC_SHA256` on the iPhone, `MessageDigest` on Android), and nonces and keys come from the platform's secure random
(`SecRandomCopyBytes`). That is authentication only, not encryption, so the iPhone keeps
`ITSAppUsesNonExemptEncryption` = NO. Since branch `crypto` the update files are common too, and the iPhone encrypts them
with CryptoKit (Apple's own encryption); `design/encryption.md` has what that means for the declaration (still NO), and
the design that replaces this section's Wi-Fi keys with the phones' own keys and encrypts the sync itself.

## 8. How it is checked

- JVM (`:shared:jvmTest`, which also runs the common tests): two to four phones in one process find each other through
  `LocalDiscovery` and sync over real sockets on 127.0.0.1 (`AutoSyncCommonTest`); the handshake's successes and
  failures: a wrong key, a replayed proof, a removed person's phone even with the key, a retired phone, a stranger, a
  phone of another move, BUSY, a phone that says it is another than it proved (`AutoHandshakeCommonTest`); the key ring
  (a removal changes the key, pair keys carry on, a phone that joined before 2.3 gets its keys at its next code sync);
  old phones (nobody dials a phone that does not advertise, code sync both ways, and the code sync of phones that hold
  keys is still 2.1.1's eight messages: `AutoSyncOldPhonesTest`); HMAC against RFC 4231 and javax.crypto.Mac; and the
  seven-phone simulation, where a third of the syncs are by themselves, every joining phone must be able to start, a
  stranger tries the handshake, and a new invariant says only phones of the move, as each knew it, sync by themselves.
- iPhone simulator (CI): the common tests, among them the handshake, the tags, HMAC (RFC 4231) and a fixed handshake
  whose bytes are written down. The UI tests: demo mode's in-app phone answers syncs by itself, so a test sees "Up to
  date with Sam's phone" on "Get fresh data" without typing a code.
- Android (Robolectric): the Sync and backup screen's line and switch; the switch is kept. Two real app instances
  cannot run in CI.
- Only real phones can show: Bonjour across a real router, the iPhone's local-network question, Android's NSD on
  different makers' phones, a sync when one phone wakes from sleep, and networks that keep devices apart. The owner's
  checklist is in `design/ios-plan.md`.

## 9. Ready for the mailbox

The later internet step needs the same things this one makes: phones that know which other phones are in the move
(the phone list and its outsiders) and a secret per move that stays on the phones (the key ring). The mailbox can
take its own sealing keys alongside the Wi-Fi keys, from the same meetings, and can reuse the status line. Nothing in
2.3 is sent anywhere but the local network.
