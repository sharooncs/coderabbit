package main

import (
	"bytes"
	"encoding/json"
	"image/png"
	"os"
	"strings"
	"testing"
)

type qrFixture struct {
	Name    string   `json:"name"`
	Text    string   `json:"text"`
	Mode    string   `json:"mode"`
	Version int      `json:"version"`
	Mask    int      `json:"mask"`
	Rows    []string `json:"rows"`
}

func readQRFixtures(t *testing.T, path string) []qrFixture {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var f struct {
		Matrices []qrFixture `json:"matrices"`
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	if len(f.Matrices) == 0 {
		t.Fatalf("no matrices in %s", path)
	}
	return f.Matrices
}

// The QR codes are ZXing's, module for module: the app's golden fixtures (labels and sync codes, ZXing 3.5.3 on Android
// 2.1.1) and setup codes ZXing made (testdata/setup-qr.json).
func TestQRIsZXings(t *testing.T) {
	all := append(readQRFixtures(t, "../shared/src/jvmTest/resources/golden/qr.json"), readQRFixtures(t, "testdata/setup-qr.json")...)
	for _, f := range all {
		code, err := encodeQR(f.Text)
		if err != nil {
			t.Fatalf("%q: %v", f.Text, err)
		}
		if code.mode != f.Mode || code.version != f.Version || code.mask != f.Mask {
			t.Errorf("%q: mode %s version %d mask %d, ZXing %s %d %d", f.Text, code.mode, code.version, code.mask, f.Mode, f.Version, f.Mask)
		}
		for y, row := range f.Rows {
			var got strings.Builder
			for x := 0; x < code.size; x++ {
				if code.dark(x, y) {
					got.WriteByte('#')
				} else {
					got.WriteByte('.')
				}
			}
			if got.String() != row {
				t.Fatalf("%q row %d:\n got %s\nwant %s", f.Text, y, got.String(), row)
			}
		}
	}
}

func TestQRPictures(t *testing.T) {
	code, err := encodeQR(setupCode(strings.Repeat("ab", 32), "https://example-laptop.example-tailnet.ts.net"))
	if err != nil {
		t.Fatal(err)
	}
	img, err := png.Decode(bytes.NewReader(qrPNG(code, 8)))
	if err != nil {
		t.Fatal(err)
	}
	if w := img.Bounds().Dx(); w != (code.size+8)*8 {
		t.Errorf("the PNG is %d pixels wide, want %d", w, (code.size+8)*8)
	}
	// The top-left finder's corner is dark; the quiet zone is light.
	if r, _, _, _ := img.At(4*8+1, 4*8+1).RGBA(); r != 0 {
		t.Error("the finder's corner is not dark")
	}
	if r, _, _, _ := img.At(1, 1).RGBA(); r == 0 {
		t.Error("the quiet zone is not light")
	}
	lines := strings.Split(strings.TrimRight(qrTerminal(code), "\n"), "\n")
	if len(lines) != (code.size+4+1)/2 {
		t.Errorf("%d lines in the terminal, want %d", len(lines), (code.size+4+1)/2)
	}
	if _, err := encodeQR(""); err == nil {
		t.Error("an empty text made a QR code")
	}
}
