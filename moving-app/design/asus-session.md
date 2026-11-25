# Instructions for a Claude Code session on the owner's Windows laptop

You are running on the owner's always-on Windows 11 laptop (an ASUS), in PowerShell. A cloud session works on HouseMove's
code. It cannot reach this laptop, so you do the parts that need it: putting HouseMove's internet mailbox on this laptop,
keeping it running from start-up, and opening it to the internet with Tailscale Funnel. The mailbox is a small program
(`moving-app/mailbox/`, its README says what it does) that keeps sealed messages for the phones of a move until each phone
fetches its own. It never holds a key that opens them. The design is `moving-app/design/encryption.md`, "Mailbox as built".

The laptop already has Tailscale. The phones will reach the mailbox at the laptop's fixed Funnel address,
`https://<machine>.<tailnet>.ts.net`, with a normal certificate.

## Rules
- **Never print, paste or read out the house token** (`data\house-token`) or the setup code (`HMBOX:1:…`) into this chat,
  a report or a commit. Don't `Get-Content` the token file or the program's terminal output that shows the code. The
  program writes the code only as a picture (`data\setup-code.png`) and shows it in a terminal only when it runs in one;
  run it in this session only in the ways below, whose output holds neither.
- **Never commit or push the laptop's address, its tailnet name or the token**, and never write them into a file in the
  repository. The owner's machine and tailnet names stay on this laptop. In your report, write the address as
  `https://<machine>.<tailnet>.ts.net`.
- No pushing: this laptop has no GitHub sign-in for the repo, and the cloud session commits. If a file in the repo must
  change, make the change locally and put the whole diff in your report.
- **Ask the owner before changing power settings, the firewall, or anything in Tailscale's settings**, and say exactly what
  you will run. Anything that needs administrator rights shows a Windows prompt (UAC) that the owner answers.
- No `--force` deletes of the data folder: it holds the mailboxes. If something is wrong with it, stop and report.

## Where things go
- The program and its files: `%LOCALAPPDATA%\HouseMoveMailbox\` (for example `C:\Users\<owner>\AppData\Local\HouseMoveMailbox`):
  `housemove-mailbox.exe`, `mailbox.json` (the settings, written at the first start), `data\` (the house token, the setup
  code's picture, the mailboxes) and `mailbox.log`. It is the owner's own folder: no administrator rights, nothing shared.
- The repository: `%USERPROFILE%\Developer\coderabbit`.

## Steps
1. **Get the code.** If `%USERPROFILE%\Developer\coderabbit` exists: `git -C $env:USERPROFILE\Developer\coderabbit fetch` and
   `git checkout <branch the cloud session names>` and `git pull`. Otherwise `git clone https://github.com/sharooncs/coderabbit.git`
   into `%USERPROFILE%\Developer` and check out that branch. If `git` is missing, ask the owner to install Git for Windows
   (or `winget install --id Git.Git -e`).
2. **The program, checked.** The committed `moving-app\mailbox\dist\housemove-mailbox.exe` has the SHA-256 written in
   `moving-app\mailbox\README.md` ("Building the .exe"). Check it:
   `(Get-FileHash moving-app\mailbox\dist\housemove-mailbox.exe -Algorithm SHA256).Hash` must equal it (in capitals).
   If Go is installed (`go version`), also build it from the source and compare, which shows the .exe is that source:
   ```
   cd moving-app\mailbox
   $env:GOTOOLCHAIN="go1.24.7"; $env:CGO_ENABLED="0"; $env:GOOS="windows"; $env:GOARCH="amd64"
   go build -trimpath -buildvcs=false -ldflags="-s -w -buildid=" -o $env:TEMP\housemove-mailbox.exe .
   (Get-FileHash $env:TEMP\housemove-mailbox.exe).Hash
   ```
   (`GOTOOLCHAIN` fetches Go 1.24.7 if another Go is installed.) Both hashes must be the README's. If they differ, stop
   and report both. Then `go test ./...` in that folder, and report the result. Without Go, say so; the hash check is enough.
3. **Put it in place.** `New-Item -ItemType Directory -Force $env:LOCALAPPDATA\HouseMoveMailbox`, copy the checked .exe
   there, and run `& $env:LOCALAPPDATA\HouseMoveMailbox\housemove-mailbox.exe --version` (it says `housemove-mailbox 1.0.0`).
4. **Tailscale.** `tailscale status` (if `tailscale` is not on the PATH: `& "C:\Program Files\Tailscale\tailscale.exe" status`).
   Report whether the laptop is connected and its machine name, but write the tailnet's name as `<tailnet>` in your report.
   - Tailscale on Windows must stay connected when nobody is logged in, or the phones lose the mailbox whenever the
     laptop restarts: check "Run unattended" (Tailscale's menu in the system tray, Preferences, Run unattended; or
     `tailscale up --unattended` with the owner's agreement, since `up` can change other settings: prefer the menu).
   - Funnel needs HTTPS certificates and MagicDNS on, and the `funnel` attribute for this machine in the tailnet's access
     policy. These are set by the owner in the browser, in the Tailscale admin console (DNS: MagicDNS and HTTPS
     Certificates; Access controls: `"nodeAttrs": [{"target": ["autogroup:member"], "attr": ["funnel"]}]`). Step 6 tells you
     if they are missing: `tailscale funnel` prints a link to allow it. Give the owner that link; don't try to change the
     policy yourself.
5. **The first start, and the setup code.** The first start makes the house token and the setup code. Start it once in a
   way whose output does not come back into this session, and that the owner can see: a new PowerShell window.
   ```
   Start-Process powershell -ArgumentList '-NoExit', '-Command', "& '$env:LOCALAPPDATA\HouseMoveMailbox\housemove-mailbox.exe'" -WorkingDirectory $env:LOCALAPPDATA\HouseMoveMailbox
   ```
   It writes `mailbox.json` (127.0.0.1, port 8787), asks Tailscale for the address, writes `data\setup-code.png`, and in
   that window shows the code. Ask the owner to close that window (Ctrl+C) once it says it is listening: from now on the
   scheduled task below runs it. If it said Tailscale did not give the address, put `"public_url": "https://<machine>.<tailnet>.ts.net"`
   in `mailbox.json` yourself from `tailscale status --json` (`Self.DNSName`, without the dot at the end) and run
   `housemove-mailbox.exe --setup-code` (its output, when it is not a terminal, says only where the picture is).
   Don't open or describe the picture's contents.
6. **Funnel.** With the owner's agreement: `tailscale funnel --bg 8787` (the port in `mailbox.json`). It serves
   `https://<machine>.<tailnet>.ts.net/` on port 443 and passes it on to `http://127.0.0.1:8787`, and keeps doing so after
   restarts (`--bg`). If it says Funnel is not enabled for the tailnet or the machine, give the owner the link it prints
   and wait; then run it again. `tailscale funnel status` shows what is served.
7. **Start at boot, without anyone logging in.** A Task Scheduler task, not a Windows service: the program is standard
   library Go and does not speak the service control protocol, and a task can run as the owner's own account (so the data
   stays in the owner's folder) without storing a password, as the program needs only local files and 127.0.0.1. Ask the
   owner to approve the Windows prompt; register it from an administrator PowerShell (`Start-Process powershell -Verb RunAs`):
   ```
   $dir = "$env:LOCALAPPDATA\HouseMoveMailbox"
   $action = New-ScheduledTaskAction -Execute "$env:ComSpec" -Argument "/c `"$dir\housemove-mailbox.exe`" --config `"$dir\mailbox.json`" >> `"$dir\mailbox.log`" 2>&1" -WorkingDirectory $dir
   $trigger = New-ScheduledTaskTrigger -AtStartup
   $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType S4U -RunLevel Limited
   $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
   Register-ScheduledTask -TaskName "HouseMove mailbox" -Action $action -Trigger $trigger -Principal $principal -Settings $settings
   Start-ScheduledTask -TaskName "HouseMove mailbox"
   ```
   (Run the first two lines in the owner's own PowerShell first and pass the resolved `$dir` into the administrator one, so
   it is the owner's folder, not the administrator's.) S4U is "Run whether user is logged on or not" with "Do not store
   password". The log gets one line per request, with no IDs, tokens or contents (the README says what), and is safe to read.
   Check: `Get-ScheduledTask -TaskName "HouseMove mailbox" | Get-ScheduledTaskInfo` (LastTaskResult 267009 means running),
   and `Invoke-RestMethod http://127.0.0.1:8787/v1/health`.
8. **Power, with the owner's agreement.** The laptop must not sleep on mains power, and must stay awake with its lid shut.
   Show the owner these first, then run them (no administrator rights needed for the current power plan):
   ```
   powercfg /change standby-timeout-ac 0
   powercfg /change hibernate-timeout-ac 0
   powercfg /setacvalueindex SCHEME_CURRENT SUB_BUTTONS LIDACTION 0
   powercfg /setactive SCHEME_CURRENT
   ```
   (`LIDACTION 0` is "Do nothing" when the lid closes on mains power; on battery nothing changes.) Report the values before
   and after (`powercfg /query SCHEME_CURRENT SUB_BUTTONS LIDACTION`, `powercfg /query SCHEME_CURRENT SUB_SLEEP`). If the
   laptop uses Modern Standby (`powercfg /a` lists "Standby (S0 Low Power Idle)"), say so: it may still drop the network
   when the lid closes, and the owner should test step 10 with the lid shut.
   No firewall rule is needed: the program listens on 127.0.0.1 only, and Tailscale reaches it there. If Windows asks
   about the firewall anyway, the owner can say no.
9. **Through the internet.** From this laptop: `Invoke-RestMethod https://<the address>/v1/health` must say `ok: True` and
   the version. (You may use the real address in the command; don't write it into your report.) Ask the owner to open the
   same address followed by `/v1/health` on a phone with Wi-Fi off, so it goes over the mobile network: it should show
   `{"ok":true,…}`.
10. **Restart test**, when the owner agrees: restart the laptop, don't log in (or log in and lock), and check step 9 from a
    phone after two minutes. Then lid shut on mains power, the same.
11. **The setup code for the owner.** Tell the owner where the picture is (`%LOCALAPPDATA%\HouseMoveMailbox\data\setup-code.png`)
    and to open it themselves on the laptop's screen. On an Organiser's Android phone: HouseMove, More, Sync and backup,
    Internet mailbox, Scan the mailbox's code. On an iPhone: point the Camera at it and tap Open in HouseMove. The other
    phones of the move get it at their next sync with that phone. Never send the picture or its text anywhere.
12. **Report** to the owner, to paste back to the cloud session:
    - each step as pass or fail, with the exact error text (with the address and tailnet written as placeholders);
    - the two SHA-256 values (committed and, if built, from the source) and the `go test` result;
    - Tailscale: connected, Run unattended on or off, Funnel status (without the address);
    - the scheduled task's state and last result, and the health check from the laptop and from a phone;
    - the power settings before and after, and whether the laptop has Modern Standby;
    - the whole diff of any change you made in the repository (nothing is pushed from here).

## Later
- A new house token (if the code got out): `housemove-mailbox.exe --new-token` in a new window the owner can see, as in
  step 5. Registered mailboxes keep working; an Organiser scans the new code.
- An update of the program: stop the task (`Stop-ScheduledTask -TaskName "HouseMove mailbox"`), check and copy the new .exe
  as in steps 2 and 3 (the data folder stays), start it again.
- Stopping it for good: `tailscale funnel reset` (or as `tailscale funnel --help` says for the version installed), then
  `Unregister-ScheduledTask -TaskName "HouseMove mailbox"`. The
  phones then say they cannot reach the mailbox, and keep syncing on Wi-Fi as before.
