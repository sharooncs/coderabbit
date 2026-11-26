# HouseMove

An Android app for running a house move from the first photo to the last
unpacked box: list what you own, decide what happens to it, pack, load,
unload, unpack. It works offline. There is no account and no server of anyone
else's; phones share the move by syncing directly with each other, and, when the
family wants it, through a mailbox on its own always-on laptop that can read none
of it.

This is version 2.5. What is new in it:

- **Keep up to date when you are not together.** Phones of a move that are not on the same Wi-Fi now keep each other up
  to date over the internet, through a small "mailbox" program on the family's always-on laptop. A change made at the
  new house reaches the phone at the old one within a minute or two while HouseMove is open on both, with no code and no
  file to send.
- **The laptop cannot read any of it.** Each message is sealed on the phone for one other phone of the move, with that
  phone's own key, and only that phone can open it. The laptop only keeps sealed messages until each phone fetches its
  own: it never has a key that opens one. A phone takes in only what a phone of the move sealed, with the key it knows for
  that phone.
- **Set up once, by an Organiser.** The laptop shows a code (`design/asus-session.md` sets the laptop up); an Organiser
  scans it in More, Sync and backup, Internet mailbox (on an iPhone, with the Camera). The other phones get it at their
  next sync. Sync and backup says how it stands ("Up to date over the internet · 2 min ago", or what went wrong, in plain
  words), and a switch turns it off on a phone.
- **Someone taken out of the move is shut out at once**, over the internet as on the Wi-Fi: the phone that removed them
  stops sending to their phone and takes nothing from it, and every phone does once it hears.
- **Update files show who sent them.** Between phones with 2.5, Send update seals the file so the phone that opens it
  sees which phone made it: "Update from Priya's phone". An update made the older way (from a phone on 2.4, or to one)
  cannot show that, and a phone with 2.5 asks before it opens one.
- **Syncing on the same Wi-Fi is unchanged**, and phones on 2.4 keep syncing with 2.5 phones as before (on the Wi-Fi only).
- **Nothing to allow.** No new permission (HouseMove already had internet, for book titles).
- **Install it over 2.4** as before: every move on the phone is kept.

Version 2.4 made syncing private: two phones that both have it sync encrypted, by themselves or by a code, each checking
the other's own key, and phones on older versions keep syncing by code, unencrypted, with both phones saying so. Its codes
are ten letters and numbers beginning with E2, and a phone taken out of the move is shut out at once.

Version 2.3 made the phones of a move find each other on the same Wi-Fi and
sync by themselves while HouseMove is open, with a line on Sync and backup and
the switch **Sync by itself on Wi-Fi**.

Version 2.2 fixed number boxes that took "NaN" and then stopped saving every
change made after it; a phone that cannot save now says so and keeps trying.

Version 2.1.1 added renaming rooms where they are added: each room in setup has
a pencil, and a new name is a tap (Guest room, Kids' room...), said or typed.
More, Rooms does the same. A room's door-sign code follows its new name until a
box or labelled item is marked with it.

What is new in 2.1:

- **Several moves on one phone.** First start asks "Start a new move" or "Join
  a move". The move's name at the top opens **Your moves**: a card per move with
  your role, days to go, progress and when it last synced.
- **One app, shaped by role.** An Organiser's Home shows approvals and the
  phases, a Decider's what is waiting for a decision, a Helper's the next
  physical job. Helpers are never sent prices, values, serial numbers, receipts
  or costs.
- **Safer joining.** The joining phone asks "Join Maple Road as Priya,
  Decider?" before anything is sent, and the Organiser taps Right or Undo.
  Helpers can share one Helper code.
- **Working apart.** Send update makes a file, encrypted to every Organiser's
  and Decider's phone, for any messaging app. Ask for decisions sends family who
  never visit a picture list to answer.
- **Safety nets.** A daily small backup to a folder you pick once, "Lost your
  phone?" on first start, Remove from this phone that first lists what no other
  phone has, and Helpers' copies that clear themselves after the move.
- **Storage as a stop**, hand-overs marked Paid or Not paid, damage reports and
  box contents that merge without losing anyone's progress.

Version 2.0 brought the dark glass look, roles and approvals, containers, and
screens that need almost no typing.

## Install

Download `releases/HouseMove-2.5.apk` from this repository (or scan the
install code that comes with each release), or build it (below). Copy it to the
phone and open it. A phone that already has HouseMove can pass it on without
internet: Who's helping, Share the app. Android will ask to allow
installing from that source. Needs Android 8.0 or newer.

Every build is signed with the key in `keystore/`, so a newer APK installs over
an older one and keeps its data.

## Kinds of use

At setup you say what you are using it for. That sets the wording, the
checklist and which parts of the app appear:

| You pick | What you get |
| --- | --- |
| Moving it myself | Everything: both homes, packing, the van, the timeline, costs |
| Movers are doing it | The same, with "truck" for "van" and a checklist for booking movers |
| Putting things in storage | From "Home" to a "Storage unit": packing and loading, without the home records and the unpacking |
| Just listing what I own | One home, the list of things and devices, reports. No boxes, no move day |

Any part can be switched off later in Settings ("Parts of the app"). Nothing is
deleted by switching a part off; it only leaves the screens.

## How the move runs

| Stage | In the app |
| --- | --- |
| Set up | What it is for, the move date, the rooms of the old home by a tap each; the new home can take the same rooms in one tap |
| Catalogue | Walk-through: one photo per big thing, name it later (or say it) |
| Devices | Make, model, serial number (scanned, photographed or typed), receipt, warranty, accessories |
| Decide | Triage: Move, Sell, Donate, Bin or Store, one tap per thing |
| Plan | A new-home room for every item; door-width fit check; load estimate |
| Prepare | Timeline of tasks counted back from move day; supplies and costs |
| Label | Print a sheet of QR labels, or write the short code the app gives you |
| Pack | Scan a label, photograph the open box, tap or say what is inside, seal |
| Books etc. | Scan the barcode already on a book or product into its box; search finds it later |
| Move day | Record both homes, scan onto the van, final sweep, scan off the van |
| Reconcile | Anything loaded but not delivered is listed before the van goes back |
| Unpack | Boxes in priority order; search finds which box anything is in |
| Close out | Inventory PDF/CSV, damage report with before and after photos |

Home shows the old home as a small lit house, three numbers that say where the
move stands, the next thing to do, the six phases of the move, and what needs
attention. The "More" button opens "Everything": every screen once, grouped by
phase.

### Containers

Furniture, appliances and valuables are catalogued one by one. Everything small
is catalogued by container, at the moment it is packed. A container is a carton,
a suitcase, a bag, a crate or tub, a wardrobe box, or anything else. Each has
its own code and can be scanned, loaded, unloaded and unpacked like a carton.

- A catalogued item can be put in a container (the kettle in box L004). It is
  then wherever its container is.
- A container can go inside another, one level deep (a toiletry bag in a
  suitcase). Scanning either one at the van moves the outer one.
- Suitcases start out marked "travels with me".

### Barcodes

The app reads three kinds of code and keeps them apart so one is never
mistaken for another:

- **HouseMove labels** (the printed QR stickers): identify a container or an
  item on move day. Only these are accepted when loading and unloading.
- **Book and product barcodes** (ISBN, EAN, UPC): scanned into a box from the
  box's page. A book exists once, so scanning it into another box moves it;
  identical products can be in several boxes.
- **Serial-number barcodes** on the back of devices: fill in the serial number.

"Scan a label" on Home accepts all three and opens whatever the code belongs
to. It also takes a sync code or an invite and goes straight to syncing.

The only online feature is looking up a book's title from its barcode in Open
Library. It is off until you agree the first time you scan a book, sends only
the barcode number, and can be changed in Settings.

## People and roles

A move can be run by one person on one phone. Nothing about roles is shown
then, and nothing is restricted.

When more people help, an Organiser adds each of them under People (Home, the
round button at the top right) and gives them one of three roles:

| Can do | Organiser | Decider | Helper |
| --- | --- | --- | --- |
| Add people, change roles, remove people; move settings, erase, restore a backup | yes | no | no |
| Decide keep/sell/donate/bin, prices, costs, the room plan, tasks | yes | suggests | no |
| Carry out a decision (mark sold, gone, paid, attach a receipt) | yes | once approved | no |
| Photograph, pack, scan on and off the van, unpack, report damage, search, ask for a decision | yes | yes | yes |

- **Joining.** Who's helping (right after setup, or under People) adds each
  person and shows their invite. The new phone taps "Join a move" and scans it,
  then answers "Join Maple Road as Priya, Decider?" before anything is sent. The
  Organiser then sees "Joined: Priya, on Priya's phone" and taps Right or Undo.
  Helpers can all use one Helper code. An invite works once and for ten
  minutes, and joining always happens once in person.
- **What each person sees.** Screens show only what their person may do. A
  Helper's Home shows the next physical job (pack, load, unload, unpack),
  their jobs for today and their hand-overs; a Helper's list of screens leaves
  out triage, the room plan, costs and devices, and a Helper's phone is never
  sent costs, receipts, prices, values, serial numbers, make and model. A
  hand-over shows every Helper "Paid" or "Not paid"; only the Helper doing it
  sees the amount. Nobody is
  flagged something only someone else can put right: a move with no date is
  pointed out to Organisers, overdue jobs to whoever may tick them off.
- **A move always has an Organiser.** The last one cannot be demoted or
  removed. Someone removed can no longer sync; what they did stays.
- The rules live in one place, `data/People.kt`, and are checked again when
  phones merge their data, so a changed app on one phone cannot overrule them.

### Suggestions and approvals

A Decider does not change a decision directly. They suggest it (sell the sofa
for 80, put the TV in the office, a cost, a task) and it waits. A Helper who is
unsure about a thing asks for a decision.

Any one Organiser answers. Waiting suggestions are counted on Home and on the
dock's Home button, and listed under Approvals: Approve, or Decline with a
reason that is one tap (or spoken). Several can be approved at once. A question
is answered by tapping the decision.

The person who suggested sees what became of it on their own Home and under
"My suggestions" ("My questions" for a Helper): approved (and then theirs to
carry out), declined and why (a no is shown on their Home with its reason, or
"No reason given"), or overtaken because the Organiser decided the same thing
directly.

Who did what is kept in a shared log (People, "All activity").

## As little typing as possible

Everything that used to be a text field has a quicker way:

- **Tap**: rooms, what is in a box, prices, door widths, van sizes, reasons for
  declining and the roles are all one-tap choices.
- **Say it**: a microphone sits wherever a name or a few words are wanted. It
  uses the phone's own speech sheet, so the app needs no microphone permission.
- **Scan it**: labels, books, serial numbers, sync codes and invites.
- **Leave it**: names have defaults ("Old home", "Helper 2", "Kitchen box 1")
  that can stand.

The keyboard is still there, behind a pencil, an "Other" chip or "Type the
code". One screen opens with a text field on show: search, which also takes a
spoken word or a tap on a room.

## Several phones

Each phone holds a full copy. On the same Wi-Fi, the phones of a move find
each other and sync by themselves while HouseMove is open (from 2.3; More,
Sync and backup says how it stands and has the switch). Otherwise one phone
shows a code (More, Sync and backup, Show sync code) and the other scans it:
for joining, with a phone on an older HouseMove, for a phone with a new key, or
on Wi-Fi that keeps devices apart. Both must be on the same Wi-Fi, or one on the
other's hotspot. Data and photos go both ways; if the same record was changed
on both, the most recent change by someone allowed to make it wins. From 2.4,
two phones that both have it sync encrypted, each checking the other's own key
(`design/encryption.md`, Part 2); how phones find each other without a code is
in `design/auto-sync.md`.

- Set the move up on one phone only. Other phones join with an invite.
- A phone can hold several moves. Each has its own ID, checked before any data
  moves, so a sync code, an update file or a backup never mixes two moves.
- Away from the others, **Send update** (on the move's card or under Sync) makes
  a file for any messaging app; only Organisers' and Deciders' phones can open
  it, with **Open update** on the move's card.
- A move with one Organiser saves a small daily backup (no ordinary photos) to a
  folder picked once. "Lost your phone?" on first start restores it.
- Each phone has its own letter; handwritten box codes start with it.
- Keep every phone on the same version of the app. Phones on different versions
  refuse to sync, so newer details are never lost on an older phone.
- Sync whenever the phones are together, and the evening before move day.
- Between phones with 2.4 the sync is encrypted. With a phone on an older
  HouseMove it is not, and both phones say so: sync on a network you trust, or
  update that phone first.
- Not on the same Wi-Fi: once an Organiser has set up the internet mailbox (More, Sync and backup, Internet mailbox), the
  phones with 2.5 keep each other up to date through it while HouseMove is open, sealed so that the laptop it runs on
  reads nothing (`design/encryption.md`, "Mailbox as built"; the laptop's side is `mailbox/`).

A backup file (zip of data and photos) can be saved or shared from the same
screen by an Organiser or a Decider (Helpers cannot make backups). Restoring
one makes the phone the person whose phone made it; a phone already in the move
merges it and stays itself. Keep backup files to yourself: they let a phone
become you.

## Build

Requires JDK 21 and the Android SDK (platform 37, which Compose 1.12 is compiled
against; the app still targets Android 15, API 35). The build downloads platform 37
and build tools 36 when they are missing and the SDK licence is accepted. `./gradlew`
fetches Gradle 9.7.0 the first time.

```
./gradlew :shared:jvmTest                 # the data and sync tests on a plain JVM, under a minute
./gradlew :app:testDebugUnitTest          # the screens and PDFs on simulated Android, about 5 minutes
./gradlew :app:assembleRelease            # app/build/outputs/apk/release/app-release.apk
./gradlew :app:connectedDebugAndroidTest  # on a connected phone or running emulator
./gradlew :sharedUi:desktopTest           # the screens as the iPhone shows them, and its model, rendered to sharedUi/build/screens/*.png
```

The iPhone app (`iosApp/`) is built on a Mac with `tools/ios.sh`; see `design/ios-plan.md`.

The internet mailbox for the laptop (`mailbox/`, Go 1.24, standard library only):

```
cd mailbox && go test ./...      # its own tests, a second or two
mailbox/build.sh                 # dist/housemove-mailbox.exe for Windows, reproducibly; build.sh check compares it
```

## How it is tested

Four layers, all free to run:

1. **Rules and edge cases** (`DataTest`, `EdgeCaseTest`, `PeopleTest`,
   `ProposalTest`, `MergeRulesTest`, `SyncPeopleTest`, `ContainerTest`,
   `BarcodeTest`). Who may do what, suggestions passing each other, merge rules,
   phones with clocks that disagree, the same box edited on two phones, a
   changed copy of the app trying to overrule an Organiser, containers inside
   containers, damaged or newer-version data files, and a 600-box move staying fast.
   These, and every other test of the data and sync code alone, are in `shared/`
   and run on a plain JVM.
2. **Every screen and flow on simulated Android** (`ScreensTest`, `FlowsTest`,
   `JourneyTest`, `PeopleFlowsTest`, `ItemFlowsTest`, `ContainerFlowsTest`,
   `MoveDayFlowsTest`, `NoTypingTest`, `PdfTest`). Each screen opens with data,
   with none, and on a small phone with enlarged text. Flows are driven by
   tapping, speaking (through a stand-in) and scanning: setup, joining with an
   invite, approving and declining, packing, loading, damage, reconcile, erase.
3. **Every screen as every kind of person** (`RoleWalkTest`). An Organiser, a
   Decider, a Helper and someone alone each open every route of the app. It
   fails on a crash, and when a core screen shows a control its person may not
   use, asked of the same rules the app uses.
4. **The real app on a real Android emulator** (`DeviceTest`, run by GitHub
   Actions on Android 8.0 and 14). The whole journey from a fresh install, a photo
   taken through the camera, the scanner running on live camera frames, PDFs
   opened with Android's own renderer and their labels scanned, sync over a real
   socket, backup through the share route, and the app being rebuilt and stopped.

Syncing by itself is tested with two to four phones in one process that find
each other through a stand-in for Bonjour and sync over real sockets
(`AutoSyncCommonTest`, `AutoHandshakeCommonTest`, `AutoSyncOldPhonesTest`), and a
third of the seven-phone simulation's syncs are by themselves. The encryption of
2.4 is checked against recorded handshakes (`NoiseCommonTest`, on the JVM, the
iPhone simulator and through the iPhone's CryptoKit; `tools/noise_check.py`
checks the recording with Python), by a relay that records every byte between
two phones and finds nothing of the move in it (`SniffingTest`), and by the
simulation, whose phones run 2.2, 2.3 and 2.4 side by side.

The internet mailbox of 2.5 is tested with two and three phones in one process against a stand-in with the laptop
program's API (`MailboxCommonTest`, also on the iPhone simulator), and with three phones over HTTP against the real Go
program, built for the test (`MailboxGoServerTest`): removed phones, a key that is not the one pinned, changed, repeated,
late and out-of-order messages, a full mailbox and a laptop that is off; and every file the program keeps, every request
and answer and its log are searched for the move and find nothing. Its sealing (HPKE) is checked against the
specification's test vectors and an implementation written apart (`tools/hpke_check.py`), and on the iPhone through
CryptoKit and against CryptoKit's own HPKE. The program has its own Go tests.

What no simulation covers: a physical camera reading a printed sticker in real
light, a physical printer's scaling, speech recognition, and two phones on one
particular Wi-Fi network (some routers and guest networks stop devices talking
to each other, or do not pass on Bonjour, so phones neither sync by themselves
nor reach each other's code; a phone hotspot avoids that).

## Audit tools

Run from this folder. They use the system `gradle` when it is the version the wrapper
names, and `./gradlew` otherwise.

| Tool | What it does | Where the result is |
| --- | --- | --- |
| `tools/test.sh` | The whole JVM suite (`shared/`, `app/` and `sharedUi/`), with a short summary. Arguments go to Gradle, e.g. `--tests 'app.housemove.FlowsTest'`; with `--tests`, only the module holding those tests runs | the terminal |
| `tools/checks.sh` | Automated checks over every screen: touch targets under 48dp, controls a screen reader cannot name, low-contrast text, text cut off on a small phone, a tall narrow phone and with large text, text fields on show, and speed with a large move. Findings never fail it | `app/build/audit/report.md` |
| `tools/audit.sh` | Pictures of every screen with realistic data, as contact sheets to look at: whole screens, first view, with no data, dialogs and camera views | `app/build/audit/sheets/` |
| `tools/roles.sh` | Every screen as an Organiser, a Decider, a Helper and someone alone see it, what can be tapped on each, and what was found; plus a contact sheet per role | `app/build/audit/roles/<who>/` |
| `tools/test.sh --tests 'app.housemove.PeopleFlowsTest'` | Among other things, counts the taps of the main flows | `app/build/audit/flows.md` |

To have a new screen checked and pictured, add its route to `ROUTES` in
`AuditSampleData.kt`. The role walk finds new routes by itself; one that takes
an argument (an id) needs an example in `stops()` in `RoleWalkTest.kt`.

## The look

"Night house": a deep navy ground with soft glows, mint as the accent, and
translucent glass for every panel, chip and button. The reference picture is
`design/look-d/look-d.png`. Screens are built from a kit so they look and
behave alike; its rules of thumb are one main action per screen, visible
without scrolling; pictures and colour before words; at most one short hint per
section; rarely used things folded away.

## Code map

Three Gradle modules. `shared/` is a Kotlin Multiplatform module with the move's
data and sync in common Kotlin (`src/commonMain/kotlin/app/housemove/`), built for
Android, for a plain JVM, where its tests run (`src/jvmTest/`, and `src/commonTest/`,
which also runs on the iPhone simulator), and for the iPhone. What it needs of the
platform is behind small seams (`Files.kt`, `Threads.kt`, `Platform.kt`, `sync/Net.kt`,
`Crypto.kt`), whose Android/JVM side is in `src/jvmCommonMain/` with the JCA and
zxing, and whose iPhone side is in `src/iosMain/` (`design/ios-step1.md`). The update
files are common; their crypto is the JCA on Android and CryptoKit, in Swift, on the
iPhone (`design/encryption.md`).
`sharedUi/` (Compose Multiplatform) holds the screens, shared by the Android app and the
iPhone app (`design/ios-plan.md`, step 2): the theme, fonts and icons, the kit and every
screen (`ui/`, `ui/screens/`), and the iPhone's own screens (`ui/iphone`: join a move that
lives on an Android phone, get fresh data, leave it). What the screens need of the phone
(the camera and scanner, photos already on it, speech, files to share, open and save, the
backup folder, the PDFs, toasts) is one interface, `ui/Platform.kt`: the Android app
implements it with the code it always ran, and the iPhone leaves out what waits for step 3.
`app/` is the Android app (`src/main/java/app/housemove/`): the activity, the screens' Android
side (`ui/AndroidPlatform.kt`: CameraX, the system's pickers and share sheet, speech, Toast;
`ui/AppRoot.kt`), the PDFs, with the tests that need simulated Android (`src/test/`) or a
device (`src/androidTest/`).

- `data/` (in `shared/`) – the model, the JSON store, merge, backup, move logic (fit check,
  volume, search). `People.kt`, `Rules.kt` and `Changes.kt` hold who may do what.
  `Moves.kt` holds the moves on a phone, `Phone.kt` the phone's ID, letters and
  label ranges; `Hidden.kt`, `Records.kt` and `Where.kt` the records Helpers are
  not sent, damage and contents lines, and where things are; `Crypto.kt`,
  `Sealed.kt` and `Update.kt` the update files; `DailyBackup.kt` the daily
  backup; `Numbers.kt` how every typed or spoken number is read, and what is refused
- `sync/` (in `shared/`) – phone-to-phone sync over a local socket, and invites;
  syncing by itself (`AutoSync`, `AutoSession`, `Discovery`: Bonjour, the
  handshake); the encryption from 2.4 (`Noise`, `SecureChannel`: the handshakes
  and the encrypted channel; `Pins`: the keys each phone accepts); the internet mailbox
  from 2.5 (`Hpke`: the sealing; `Mailbox`: the code, the envelopes and the laptop's API;
  `MailboxSync`: registering, sending and reading; `Http`: HTTPS on each platform)
- `pdf/` (in `app/`) – QR label sheets, door signs, inventory and damage reports, drawn by Android; the
  sheets' sizes and the Helper card's and Ask for decisions' words are shared (`sharedUi` `pdf/`)
- `ui/` (in `sharedUi/`) – the kit: `Theme.kt` (colours, type), `Glass.kt` (panels, pills, orbs),
  `Visual.kt` and `Common.kt` (tiles, buttons, inputs), `Dock.kt` (the dome at
  the bottom), `Iso.kt` (the house drawings), `PeopleKit.kt` (people and roles
  on screen), `Speak.kt` (the microphone), `Camera.kt` (photos and scanners),
  `Journey.kt` (the phases of a move), `Nav.kt` (the routes), `Platform.kt` (what the screens ask of the phone)
- `ui/screens/` (in `sharedUi/`) – one file per screen or group of screens
- `tools/` – the scripts above
- `mailbox/` – the internet mailbox for the family's laptop (Go): it keeps sealed messages for the phones of a move. Its
  README says how to run it and its API; `design/asus-session.md` how a Claude Code session on the laptop sets it up
