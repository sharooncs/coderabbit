# HouseMove's internet mailbox

A small program for an always-on computer (the family's Windows laptop) that keeps sealed messages for the phones of a
move until each phone fetches its own. Phones that are not on the same Wi-Fi keep each other up to date through it
(HouseMove 2.5; `design/encryption.md`, "Mailbox as built").

It only ever stores and forwards sealed bytes. Each message is sealed on the phone, with HPKE, to the key of the one phone
it is for: the program has no key that opens one, and never looks inside. What it sees is which mailbox an envelope is
for, its size and when it came.

- **Where it listens.** 127.0.0.1 only, on the port in its settings (8787 by default). Tailscale Funnel gives it a fixed
  `https://<machine>.<tailnet>.ts.net/` address with a normal (Let's Encrypt) certificate; nothing else reaches it.
- **Go standard library only.** No other code is in it.
- **Version 1.0.0.** `housemove-mailbox --version` says it; `GET /v1/health` too.

Setting it up on the laptop, keeping it running at start-up and opening it to the internet: `design/asus-session.md`.

## Running it

```
housemove-mailbox                 run; the settings are mailbox.json beside the program (written at the first start)
housemove-mailbox --config FILE   run with another settings file
housemove-mailbox --setup-code    write the setup code's QR code again, and stop
housemove-mailbox --new-token     make a new house token and its setup code, and stop
housemove-mailbox --version       say the version, and stop
```

**The house token and the setup code.** At its first start the program makes a house token (32 random bytes, 64 hex
digits) and keeps it in `data/house-token`. Registering a mailbox takes it; nothing else does. The setup code holds it,
with the program's public address:

```
HMBOX:1:<house token>:https://<machine>.<tailnet>.ts.net
```

The program writes it as a QR code to `data/setup-code.png` and says where that is. When it runs in a terminal, it also
shows the QR code there, and the code as text; when its output goes anywhere else (a scheduled task, a log, a script
reading it) the token is never printed. An Organiser scans the QR code with HouseMove (More, Sync and backup, Internet
mailbox); on an iPhone, the Camera's "Open in HouseMove". The address comes from the settings' `public_url`, or else from
`tailscale status --json`.

`--new-token` replaces the token: mailboxes registered already go on working, and only the new code registers more. An
Organiser scans the new code; the phones pass it on to each other inside their encrypted syncs.

## Settings (`mailbox.json`)

| Setting | Default | What |
| --- | --- | --- |
| `listen` | `127.0.0.1:8787` | 127.0.0.1 (or ::1) and a port; any other address is refused |
| `data` | `data` | the data folder, relative to the settings file |
| `public_url` | (empty) | the Funnel address, for the setup code; empty: asked of Tailscale |
| `max_envelope_mb` | 8 | the largest envelope taken |
| `mailbox_quota_mb` | 64 | the most one mailbox holds |
| `total_quota_mb` | 2048 | the most all mailboxes together hold |
| `max_mailboxes` | 200 | how many mailboxes may be registered |
| `retention_days` | 30 | an envelope not fetched and deleted by then is deleted |
| `idle_days` | 90 | a mailbox not read for this long is deleted (its phone registers again) |
| `rate_per_minute` | 120 | requests one address may make a minute (bursts of half) |
| `registrations_per_hour` | 20 | mailboxes one address may register an hour |
| `failures_per_hour` | 30 | wrong tokens or secrets from one address in an hour before it is turned away for a while |
| `trust_forwarded_for` | true | for connections from this machine (Funnel's proxy), the sender's address is the last entry of `X-Forwarded-For` |

## The API

All under `/v1`, JSON unless said. A mailbox ID is 32 lower-case hex digits (128 random bits, chosen by the phone); a read
secret 64 (256 random bits, chosen by the phone, of which only the SHA-256 is kept); an envelope ID 24 (when it came, in
nanoseconds, then 4 random bytes). "token" is `Authorization: Bearer <house token>`, "secret" `Authorization: Bearer <read
secret>`.

| Request | Needs | Answers |
| --- | --- | --- |
| `GET /v1/health` | | 200 `{"ok":true,"name":"housemove-mailbox","version":"1.0.0"}` |
| `PUT /v1/mailboxes/{id}` with `{"secret":"<read secret>"}` | token | 201 registered; 200 already registered with that secret; 409 registered with another; 401 no or wrong token; 400; 507 no room for another mailbox |
| `DELETE /v1/mailboxes/{id}` | secret | 204 deleted with its envelopes; 404; 403 wrong secret |
| `POST /v1/mailboxes/{id}/envelopes`, the envelope's bytes | anyone with the ID | 201 `{"id":"<envelope id>"}`; 404 no such mailbox; 413 larger than `max_envelope_mb`; 507 over a quota; 400 empty |
| `GET /v1/mailboxes/{id}/envelopes` | secret | 200 `{"envelopes":[{"id":…,"size":…,"at":<milliseconds>}]}`, oldest first; 403; 404 |
| `GET /v1/mailboxes/{id}/envelopes/{envelope}` | secret | 200 the bytes (`application/octet-stream`); 403; 404 |
| `DELETE /v1/mailboxes/{id}/envelopes/{envelope}` | secret | 204 deleted (the phone has it); 403; 404 |

Any request may be answered 429 (too many from that address). Registering again with the same ID and secret changes
nothing, so a phone whose answer was lost just asks again. Secrets and the token are compared in constant time, as
SHA-256 hashes (`crypto/subtle`).

## Storage

Plain files in the data folder: `house-token`, `setup-code.png`, and `mailboxes/<id>/meta.json` (the secret's SHA-256, when
the mailbox was made and last read) and `mailboxes/<id>/env/<envelope id>` (the envelope's bytes, as they came). Each file
is written to a temporary file beside it, flushed to disk, then renamed over its name, so a crash or a power cut leaves
the old file or the new one; what a cut-short write leaves behind is deleted at the next start. Every hour (and at the
start) envelopes older than `retention_days` and mailboxes not read for `idle_days` are deleted.

## The log

One line per request on standard error: the time, the method, the kind of request with every ID left out
(`/v1/mailboxes/{id}/envelopes`), the status, the size of the answer and how long it took. Never a mailbox or envelope
ID, a token, a secret, an address, or anything of what an envelope holds. At the start: the version, the address it
listens on, and how many mailboxes and envelopes it holds.

## Tests

```
cd moving-app/mailbox && go vet ./... && go test ./...
```

They cover the API (registering with and without the token, posting by ID alone, reading only with the secret, deleting),
the limits (the largest envelope, both quotas, the number of mailboxes), retention and idle mailboxes on a clock of their
own, rate limits by address (and that only the last `X-Forwarded-For` entry counts), a log and files with nothing secret
in them, crash-safe files, the settings, the setup code (never printed when the output is not a terminal), many posts at
once, and the QR codes, module for module the same as ZXing's (the app's golden fixtures and `testdata/setup-qr.json`).
The app's own tests run the phones against this program over HTTP (`shared/src/jvmTest/.../MailboxGoServerTest.kt`, which
builds it with `go build` when Go is there).

## Building the .exe

```
mailbox/build.sh          # dist/housemove-mailbox.exe, and its SHA-256
mailbox/build.sh check    # builds it again in a temporary folder and compares it with the committed one
```

That is, with Go 1.24.7 (`GOTOOLCHAIN=go1.24.7` fetches it when another Go is installed):

```
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -buildvcs=false -ldflags="-s -w -buildid=" -o dist/housemove-mailbox.exe .
```

The same Go on any machine and in any folder makes the same bytes. The committed `dist/housemove-mailbox.exe` (Windows,
amd64, 6,496,256 bytes):

```
SHA-256 8c00e9bab2ca1dc6a622da3d415b2b184f29ccda4dd653a2e0f7772505b3d85b
```

On Windows, `Get-FileHash dist\housemove-mailbox.exe` (SHA-256 is its default) says the same in capitals.

The QR encoder (`qr.go`) is a port of part of ZXing 3.5.3 (Apache License 2.0, `moving-app/licenses/ZXing-Apache-2.0.txt`).
