package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// The house token: 32 random bytes, as 64 hex digits, made at the first start and kept in the data folder (house-token).
// Registering a mailbox takes it; nothing else does. It is shown once, inside the setup code, which the phones read:
//
//	HMBOX:1:<house token>:<public address>
//
// as a QR code in setup-code.png beside it, and in the terminal when the program runs in one. It is never written to the
// log, and never printed when the output does not go to a terminal (a service, a scheduled task, a script reading it).

const tokenFile = "house-token"
const setupPNG = "setup-code.png"

func tokenPath(dataDir string) string { return filepath.Join(dataDir, tokenFile) }

// loadToken reads the house token, or makes one when there is none (made is then true).
func loadToken(dataDir string) (token string, made bool, err error) {
	b, err := os.ReadFile(tokenPath(dataDir))
	if err == nil {
		t := strings.TrimSpace(string(b))
		if validSecret(t) {
			return t, false, nil
		}
		return "", false, fmt.Errorf("%s is not a house token: delete it, or run with --new-token", tokenPath(dataDir))
	}
	if !errors.Is(err, os.ErrNotExist) {
		return "", false, err
	}
	t, err := newToken(dataDir)
	return t, err == nil, err
}

// newToken makes a new house token and keeps it, in place of any other: mailboxes registered already keep working, and
// only the new token registers more.
func newToken(dataDir string) (string, error) {
	var b [32]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	t := hex.EncodeToString(b[:])
	return t, writeFileAtomic(tokenPath(dataDir), []byte(t+"\n"))
}

// setupCode is what the phones read to set up the mailbox.
func setupCode(token, publicURL string) string {
	return "HMBOX:1:" + token + ":" + strings.TrimRight(publicURL, "/")
}

// tailscaleURL asks Tailscale for this machine's name in the tailnet: https://<machine>.<tailnet>.ts.net, which Tailscale
// Funnel serves. Empty when Tailscale is not there or does not say.
func tailscaleURL() string {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "tailscale", "status", "--json").Output()
	if err != nil {
		return ""
	}
	var st struct {
		Self struct {
			DNSName string `json:"DNSName"`
		} `json:"Self"`
	}
	if json.Unmarshal(out, &st) != nil {
		return ""
	}
	name := strings.TrimSuffix(strings.TrimSpace(st.Self.DNSName), ".")
	if name == "" || !strings.HasSuffix(name, ".ts.net") {
		return ""
	}
	return "https://" + name
}

// qrPNG draws code as a PNG: black modules on white, scale pixels a module, with a quiet zone of four modules.
func qrPNG(code *qrCode, scale int) []byte {
	quiet := 4
	n := (code.size + 2*quiet) * scale
	img := image.NewGray(image.Rect(0, 0, n, n))
	for i := range img.Pix {
		img.Pix[i] = 0xff
	}
	for y := 0; y < code.size; y++ {
		for x := 0; x < code.size; x++ {
			if !code.dark(x, y) {
				continue
			}
			for dy := 0; dy < scale; dy++ {
				for dx := 0; dx < scale; dx++ {
					img.SetGray((x+quiet)*scale+dx, (y+quiet)*scale+dy, color.Gray{Y: 0})
				}
			}
		}
	}
	var buf bytes.Buffer
	png.Encode(&buf, img)
	return buf.Bytes()
}

// qrTerminal draws code with half blocks, two rows of modules a line, light modules drawn (for a dark terminal), with a
// quiet zone of two modules.
func qrTerminal(code *qrCode) string {
	quiet := 2
	n := code.size + 2*quiet
	light := func(x, y int) bool {
		x -= quiet
		y -= quiet
		if x < 0 || y < 0 || x >= code.size || y >= code.size {
			return true
		}
		return !code.dark(x, y)
	}
	var b strings.Builder
	for y := 0; y < n; y += 2 {
		for x := 0; x < n; x++ {
			top := light(x, y)
			bottom := y+1 < n && light(x, y+1)
			switch {
			case top && bottom:
				b.WriteRune('█')
			case top:
				b.WriteRune('▀')
			case bottom:
				b.WriteRune('▄')
			default:
				b.WriteRune(' ')
			}
		}
		b.WriteRune('\n')
	}
	return b.String()
}

// isTerminal says whether f is a terminal (a console), not a file or a pipe.
func isTerminal(f *os.File) bool {
	info, err := f.Stat()
	return err == nil && info.Mode()&os.ModeCharDevice != 0
}

// writeSetup writes the setup code's QR to setup-code.png in the data folder and says where it is; on a terminal it
// shows the QR there too. The token itself goes nowhere else.
func writeSetup(out io.Writer, terminal bool, dataDir, token, publicURL string) error {
	if publicURL == "" {
		fmt.Fprintln(out, "The setup code needs this laptop's Tailscale address (https://<machine>.<tailnet>.ts.net). "+
			"Tailscale did not say it: put it in the settings file as \"public_url\", then run this program with --setup-code.")
		return nil
	}
	code, err := encodeQR(setupCode(token, publicURL))
	if err != nil {
		return err
	}
	path := filepath.Join(dataDir, setupPNG)
	if err := writeFileAtomic(path, qrPNG(code, 8)); err != nil {
		return err
	}
	fmt.Fprintf(out, "The mailbox's setup code is in %s.\n", path)
	fmt.Fprintf(out, "On an Organiser's phone: HouseMove, More, Sync and backup, Internet mailbox, Scan the mailbox's code. Address: %s\n", publicURL)
	if terminal {
		fmt.Fprint(out, "\n"+qrTerminal(code)+"\n")
		fmt.Fprintf(out, "Or copy this into HouseMove: %s\n", setupCode(token, publicURL))
		fmt.Fprintln(out, "Keep the code to yourself: whoever has it can register mailboxes here (they still cannot read anything).")
	}
	return nil
}
