package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// clock is a time the tests move on by hand.
type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *clock) add(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

// lockedBuffer is the log, written from the server's goroutines.
type lockedBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *lockedBuffer) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *lockedBuffer) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

type harness struct {
	t     *testing.T
	cfg   config
	dir   string
	clock *clock
	st    *store
	srv   *server
	http  *httptest.Server
	log   *lockedBuffer
	token string
}

func randomHex(n int) string {
	b := make([]byte, n/2)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func newHarness(t *testing.T, change func(*config)) *harness {
	t.Helper()
	cfg := defaultConfig()
	if change != nil {
		change(&cfg)
	}
	dir := t.TempDir()
	cfg.Data = dir
	h := &harness{t: t, cfg: cfg, dir: dir, clock: &clock{t: time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)}, log: &lockedBuffer{}}
	token, made, err := loadToken(dir)
	if err != nil || !made {
		t.Fatalf("token: %v %v", made, err)
	}
	h.token = token
	h.st, err = openStore(dir, cfg, h.clock.now)
	if err != nil {
		t.Fatal(err)
	}
	hash := secretHash(token)
	h.srv = newServer(cfg, h.st, func() []byte { return hash }, log.New(h.log, "", 0), h.clock.now)
	h.http = httptest.NewServer(h.srv.routes())
	t.Cleanup(h.http.Close)
	return h
}

func (h *harness) do(method, path, auth string, body []byte, header ...string) (int, []byte) {
	h.t.Helper()
	req, err := http.NewRequest(method, h.http.URL+path, bytes.NewReader(body))
	if err != nil {
		h.t.Fatal(err)
	}
	if auth != "" {
		req.Header.Set("Authorization", "Bearer "+auth)
	}
	for i := 0; i+1 < len(header); i += 2 {
		req.Header.Add(header[i], header[i+1])
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, b
}

func (h *harness) register(id, secret string) int {
	h.t.Helper()
	code, _ := h.do("PUT", "/v1/mailboxes/"+id, h.token, []byte(fmt.Sprintf(`{"secret":%q}`, secret)))
	return code
}

func (h *harness) list(id, secret string) (int, []listed) {
	h.t.Helper()
	code, b := h.do("GET", "/v1/mailboxes/"+id+"/envelopes", secret, nil)
	var out struct {
		Envelopes []listed `json:"envelopes"`
	}
	json.Unmarshal(b, &out)
	return code, out.Envelopes
}

func TestHealthAndVersion(t *testing.T) {
	h := newHarness(t, nil)
	code, b := h.do("GET", "/v1/health", "", nil)
	if code != 200 || !strings.Contains(string(b), `"ok":true`) || !strings.Contains(string(b), version) {
		t.Fatalf("health: %d %s", code, b)
	}
	if code, _ := h.do("GET", "/", "", nil); code != 404 {
		t.Errorf("/ answered %d", code)
	}
	if code, _ := h.do("POST", "/v1/health", "", nil); code != 404 && code != 405 {
		t.Errorf("POST /v1/health answered %d", code)
	}
}

func TestRegisteringNeedsTheHouseToken(t *testing.T) {
	h := newHarness(t, nil)
	id, secret := randomHex(32), randomHex(64)
	body := []byte(fmt.Sprintf(`{"secret":%q}`, secret))
	if code, _ := h.do("PUT", "/v1/mailboxes/"+id, "", body); code != 401 {
		t.Errorf("no token: %d", code)
	}
	if code, _ := h.do("PUT", "/v1/mailboxes/"+id, randomHex(64), body); code != 401 {
		t.Errorf("a wrong token: %d", code)
	}
	if code, _ := h.do("PUT", "/v1/mailboxes/"+id, h.token[:63]+"0", body); code != 401 {
		t.Errorf("a token one digit off: %d", code)
	}
	if code := h.register(id, secret); code != 201 {
		t.Fatalf("registered: %d", code)
	}
	// Again with the same secret (an answer that was lost): nothing changes. With another: refused.
	if code := h.register(id, secret); code != 200 {
		t.Errorf("again: %d", code)
	}
	if code := h.register(id, randomHex(64)); code != 409 {
		t.Errorf("another secret: %d", code)
	}
	for _, bad := range []string{"short", strings.ToUpper(randomHex(32)), randomHex(30) + "zz"} {
		if code, _ := h.do("PUT", "/v1/mailboxes/"+bad, h.token, body); code != 400 && code != 404 {
			t.Errorf("mailbox ID %q: %d", bad, code)
		}
	}
	if code, _ := h.do("PUT", "/v1/mailboxes/"+randomHex(32), h.token, []byte(`{"secret":"abc"}`)); code != 400 {
		t.Errorf("a short secret: %d", code)
	}
	// Only the secret's hash is kept.
	raw, _ := os.ReadFile(filepath.Join(h.dir, "mailboxes", id, "meta.json"))
	if strings.Contains(string(raw), secret) || !strings.Contains(string(raw), hex.EncodeToString(secretHash(secret))) {
		t.Errorf("meta.json: %s", raw)
	}
}

func TestPostListFetchAcknowledge(t *testing.T) {
	h := newHarness(t, nil)
	id, secret := randomHex(32), randomHex(64)
	h.register(id, secret)
	// Anyone who knows the mailbox ID may post; nothing else is needed.
	var ids []string
	for i, body := range []string{"first sealed bytes", "second", "third"} {
		code, b := h.do("POST", "/v1/mailboxes/"+id+"/envelopes", "", []byte(body))
		if code != 201 {
			t.Fatalf("post %d: %d %s", i, code, b)
		}
		var r struct{ ID string }
		json.Unmarshal(b, &r)
		if !validEnvelopeID(r.ID) {
			t.Fatalf("envelope ID %q", r.ID)
		}
		ids = append(ids, r.ID)
		h.clock.add(time.Second)
	}
	if code, _ := h.do("POST", "/v1/mailboxes/"+randomHex(32)+"/envelopes", "", []byte("x")); code != 404 {
		t.Errorf("an unknown mailbox: %d", code)
	}
	if code, _ := h.do("POST", "/v1/mailboxes/"+id+"/envelopes", "", nil); code != 400 {
		t.Errorf("an empty envelope: %d", code)
	}
	// Reading needs the read secret.
	if code, _ := h.list(id, ""); code != 403 {
		t.Errorf("no secret: %d", code)
	}
	if code, _ := h.list(id, randomHex(64)); code != 403 {
		t.Errorf("a wrong secret: %d", code)
	}
	if code, _ := h.list(id, h.token); code != 403 {
		t.Errorf("the house token is not a read secret: %d", code)
	}
	code, list := h.list(id, secret)
	if code != 200 || len(list) != 3 {
		t.Fatalf("list: %d %v", code, list)
	}
	for i, e := range list {
		if e.ID != ids[i] {
			t.Errorf("not oldest first: %v", list)
		}
	}
	if list[0].Size != int64(len("first sealed bytes")) || list[0].At != h.clock.t.Add(-3*time.Second).UnixMilli() {
		t.Errorf("size and time: %+v", list[0])
	}
	if code, b := h.do("GET", "/v1/mailboxes/"+id+"/envelopes/"+ids[0], secret, nil); code != 200 || string(b) != "first sealed bytes" {
		t.Errorf("fetch: %d %q", code, b)
	}
	if code, _ := h.do("GET", "/v1/mailboxes/"+id+"/envelopes/"+ids[0], randomHex(64), nil); code != 403 {
		t.Errorf("fetch with a wrong secret: %d", code)
	}
	if code, _ := h.do("DELETE", "/v1/mailboxes/"+id+"/envelopes/"+ids[0], randomHex(64), nil); code != 403 {
		t.Errorf("delete with a wrong secret: %d", code)
	}
	if code, _ := h.do("DELETE", "/v1/mailboxes/"+id+"/envelopes/"+ids[0], secret, nil); code != 204 {
		t.Errorf("acknowledge: %d", code)
	}
	if code, _ := h.do("DELETE", "/v1/mailboxes/"+id+"/envelopes/"+ids[0], secret, nil); code != 404 {
		t.Errorf("acknowledged twice: %d", code)
	}
	if code, _ := h.do("GET", "/v1/mailboxes/"+id+"/envelopes/"+ids[0], secret, nil); code != 404 {
		t.Errorf("fetch what was acknowledged: %d", code)
	}
	if _, list := h.list(id, secret); len(list) != 2 {
		t.Errorf("after one acknowledged: %v", list)
	}
	if code, _ := h.do("GET", "/v1/mailboxes/"+id+"/envelopes/../meta.json", secret, nil); code == 200 {
		t.Error("a path outside the envelopes was answered")
	}
	// Unregistering takes the envelopes with it.
	if code, _ := h.do("DELETE", "/v1/mailboxes/"+id, randomHex(64), nil); code != 403 {
		t.Errorf("unregister with a wrong secret: %d", code)
	}
	if code, _ := h.do("DELETE", "/v1/mailboxes/"+id, secret, nil); code != 204 {
		t.Errorf("unregister: %d", code)
	}
	if code, _ := h.list(id, secret); code != 404 {
		t.Errorf("list after unregistering: %d", code)
	}
	if _, err := os.Stat(filepath.Join(h.dir, "mailboxes", id)); !os.IsNotExist(err) {
		t.Error("the mailbox's folder is still there")
	}
}

func TestLimits(t *testing.T) {
	h := newHarness(t, func(c *config) {
		c.MaxEnvelopeMB = 1
		c.MailboxQuotaMB = 2
		c.TotalQuotaMB = 3
		c.MaxMailboxes = 2
	})
	a, sa := randomHex(32), randomHex(64)
	b, sb := randomHex(32), randomHex(64)
	h.register(a, sa)
	h.register(b, sb)
	if code := h.register(randomHex(32), randomHex(64)); code != 507 {
		t.Errorf("a third mailbox: %d", code)
	}
	mb := bytes.Repeat([]byte{7}, 1<<20)
	if code, _ := h.do("POST", "/v1/mailboxes/"+a+"/envelopes", "", append(mb, 1)); code != 413 {
		t.Errorf("one byte over the largest envelope: %d", code)
	}
	for i := 0; i < 2; i++ {
		if code, _ := h.do("POST", "/v1/mailboxes/"+a+"/envelopes", "", mb); code != 201 {
			t.Fatalf("envelope %d: %d", i, code)
		}
	}
	if code, _ := h.do("POST", "/v1/mailboxes/"+a+"/envelopes", "", []byte("x")); code != 507 {
		t.Errorf("over the mailbox's quota: %d", code)
	}
	if code, _ := h.do("POST", "/v1/mailboxes/"+b+"/envelopes", "", mb); code != 201 {
		t.Errorf("another mailbox: %d", code)
	}
	if code, _ := h.do("POST", "/v1/mailboxes/"+b+"/envelopes", "", []byte("x")); code != 507 {
		t.Errorf("over the total quota: %d", code)
	}
	// Acknowledging makes room.
	_, list := h.list(a, sa)
	h.do("DELETE", "/v1/mailboxes/"+a+"/envelopes/"+list[0].ID, sa, nil)
	if code, _ := h.do("POST", "/v1/mailboxes/"+b+"/envelopes", "", []byte("x")); code != 201 {
		t.Errorf("after one was acknowledged: %d", code)
	}
}

func TestRetentionAndIdleMailboxes(t *testing.T) {
	h := newHarness(t, func(c *config) {
		c.RetentionDays = 30
		c.IdleDays = 90
	})
	keep, sk := randomHex(32), randomHex(64)
	idle, si := randomHex(32), randomHex(64)
	h.register(keep, sk)
	h.register(idle, si)
	h.do("POST", "/v1/mailboxes/"+keep+"/envelopes", "", []byte("old"))
	h.clock.add(29 * 24 * time.Hour)
	h.do("POST", "/v1/mailboxes/"+keep+"/envelopes", "", []byte("new"))
	if e, m := h.st.sweep(); e != 0 || m != 0 {
		t.Errorf("after 29 days: %d envelopes and %d mailboxes deleted", e, m)
	}
	h.clock.add(2 * 24 * time.Hour)
	if e, _ := h.st.sweep(); e != 1 {
		t.Errorf("after 31 days: %d envelopes deleted", e)
	}
	_, list := h.list(keep, sk)
	if len(list) != 1 {
		t.Fatalf("left: %v", list)
	}
	if code, b := h.do("GET", "/v1/mailboxes/"+keep+"/envelopes/"+list[0].ID, sk, nil); code != 200 || string(b) != "new" {
		t.Errorf("the newer one: %d %q", code, b)
	}
	// Read now and then, a mailbox stays; never read for 90 days, it goes.
	for i := 0; i < 4; i++ {
		h.clock.add(25 * 24 * time.Hour)
		h.list(keep, sk)
	}
	h.st.sweep()
	if code, _ := h.list(keep, sk); code != 200 {
		t.Errorf("a mailbox read every 25 days: %d", code)
	}
	if code, _ := h.list(idle, si); code != 404 {
		t.Errorf("a mailbox not read for 100 days: %d", code)
	}
}

func TestRateLimitsByAddress(t *testing.T) {
	h := newHarness(t, func(c *config) {
		c.RatePerMinute = 10
		c.FailuresPerHour = 3
		c.RegistrationsPerHour = 2
	})
	hit := func(from string) int {
		code, _ := h.do("GET", "/v1/health", "", nil, "X-Forwarded-For", "203.0.113.9, "+from)
		return code
	}
	// A burst of half the minute's rate, then turned away; another address is not.
	for i := 0; i < 5; i++ {
		if code := hit("198.51.100.1"); code != 200 {
			t.Fatalf("request %d: %d", i, code)
		}
	}
	if code := hit("198.51.100.1"); code != 429 {
		t.Errorf("over the rate: %d", code)
	}
	if code := hit("198.51.100.2"); code != 200 {
		t.Errorf("another address: %d", code)
	}
	// Only the last X-Forwarded-For entry (the one Funnel's proxy adds) counts: one the sender wrote changes nothing.
	if code := hit("198.51.100.1"); code != 429 {
		t.Errorf("the same last address after another first one: %d", code)
	}
	h.clock.add(time.Minute)
	if code := hit("198.51.100.1"); code != 200 {
		t.Errorf("a minute later: %d", code)
	}
	// Wrong secrets: after three, that address is turned away for a while, even with the right one.
	id, secret := randomHex(32), randomHex(64)
	for i := 0; i < 2; i++ {
		code, _ := h.do("PUT", "/v1/mailboxes/"+randomHex(32), h.token, []byte(fmt.Sprintf(`{"secret":%q}`, randomHex(64))), "X-Forwarded-For", "192.0.2.50")
		if code != 201 {
			t.Fatalf("registration %d: %d", i, code)
		}
	}
	if code, _ := h.do("PUT", "/v1/mailboxes/"+id, h.token, []byte(fmt.Sprintf(`{"secret":%q}`, secret)), "X-Forwarded-For", "192.0.2.50"); code != 429 {
		t.Errorf("a third registration within the hour: %d", code)
	}
	h.clock.add(time.Hour)
	h.do("PUT", "/v1/mailboxes/"+id, h.token, []byte(fmt.Sprintf(`{"secret":%q}`, secret)), "X-Forwarded-For", "192.0.2.60")
	for i := 0; i < 3; i++ {
		code, _ := h.do("GET", "/v1/mailboxes/"+id+"/envelopes", randomHex(64), nil, "X-Forwarded-For", "192.0.2.66")
		if code != 403 {
			t.Fatalf("wrong secret %d: %d", i, code)
		}
	}
	if code, _ := h.do("GET", "/v1/mailboxes/"+id+"/envelopes", secret, nil, "X-Forwarded-For", "192.0.2.66"); code != 429 {
		t.Errorf("after three wrong secrets: %d", code)
	}
	if code, _ := h.do("GET", "/v1/mailboxes/"+id+"/envelopes", secret, nil, "X-Forwarded-For", "192.0.2.67"); code != 200 {
		t.Errorf("from another address: %d", code)
	}
}

func TestClientAddress(t *testing.T) {
	r := httptest.NewRequest("GET", "/", nil)
	r.RemoteAddr = "127.0.0.1:5555"
	r.Header.Add("X-Forwarded-For", "10.0.0.1, 198.51.100.7")
	if got := clientAddress(r, true); got != "198.51.100.7" {
		t.Errorf("through Funnel: %s", got)
	}
	if got := clientAddress(r, false); got != "127.0.0.1" {
		t.Errorf("not trusting it: %s", got)
	}
	r.RemoteAddr = "198.51.100.8:5555"
	if got := clientAddress(r, true); got != "198.51.100.8" {
		t.Errorf("not from this machine, the header is not trusted: %s", got)
	}
	r.RemoteAddr = "127.0.0.1:5555"
	r.Header.Set("X-Forwarded-For", "not an address")
	if got := clientAddress(r, true); got != "127.0.0.1" {
		t.Errorf("a header that is not an address: %s", got)
	}
}

// The log says what kind of request came and how it went: never an ID, a token, a secret, an address or a byte of an
// envelope; the stored files hold the envelopes as they came, and no secret.
func TestNothingSecretIsLogged(t *testing.T) {
	h := newHarness(t, nil)
	id, secret := randomHex(32), randomHex(64)
	h.register(id, secret)
	body := []byte("SEALED-BYTES-" + randomHex(20))
	_, b := h.do("POST", "/v1/mailboxes/"+id+"/envelopes", "", body, "X-Forwarded-For", "198.51.100.42")
	var r struct{ ID string }
	json.Unmarshal(b, &r)
	h.list(id, secret)
	h.do("GET", "/v1/mailboxes/"+id+"/envelopes/"+r.ID, secret, nil)
	h.do("DELETE", "/v1/mailboxes/"+id+"/envelopes/"+r.ID, secret, nil)
	h.list(id, randomHex(64))
	logged := h.log.String()
	if !strings.Contains(logged, "POST /v1/mailboxes/{id}/envelopes 201") || !strings.Contains(logged, "PUT /v1/mailboxes/{id} 201") {
		t.Errorf("the log does not say what happened:\n%s", logged)
	}
	for _, secretThing := range []string{id, secret, h.token, r.ID, string(body), "198.51.100.42", "SEALED"} {
		if strings.Contains(logged, secretThing) {
			t.Errorf("the log holds %q:\n%s", secretThing, logged)
		}
	}
	filepath.Walk(h.dir, func(p string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() {
			return nil
		}
		raw, _ := os.ReadFile(p)
		if strings.Contains(string(raw), secret) {
			t.Errorf("%s holds the read secret", p)
		}
		if filepath.Base(p) != tokenFile && strings.Contains(string(raw), h.token) {
			t.Errorf("%s holds the house token", p)
		}
		return nil
	})
}

// A write cut short leaves its temporary file, which the next start deletes; a mailbox whose meta.json is damaged is
// let go (its phone registers again); what was whole is all there.
func TestCrashSafeFiles(t *testing.T) {
	h := newHarness(t, nil)
	id, secret := randomHex(32), randomHex(64)
	h.register(id, secret)
	h.do("POST", "/v1/mailboxes/"+id+"/envelopes", "", []byte("whole"))
	envDir := filepath.Join(h.dir, "mailboxes", id, "env")
	os.WriteFile(filepath.Join(envDir, "0000000000000001deadbeef.tmp-123"), []byte("half"), 0o600)
	broken := randomHex(32)
	os.MkdirAll(filepath.Join(h.dir, "mailboxes", broken, "env"), 0o700)
	os.WriteFile(filepath.Join(h.dir, "mailboxes", broken, "meta.json"), []byte("{not json"), 0o600)
	os.WriteFile(filepath.Join(h.dir, "mailboxes", "meta.json.tmp-9"), []byte("x"), 0o600)

	st, err := openStore(h.dir, h.cfg, h.clock.now)
	if err != nil {
		t.Fatal(err)
	}
	list, err := st.list(id, secret)
	if err != nil || len(list) != 1 {
		t.Fatalf("after a restart: %v %v", list, err)
	}
	if m, e, n := st.counts(); m != 1 || e != 1 || n != int64(len("whole")) {
		t.Errorf("counts %d %d %d", m, e, n)
	}
	for _, gone := range []string{filepath.Join(envDir, "0000000000000001deadbeef.tmp-123"), filepath.Join(h.dir, "mailboxes", broken), filepath.Join(h.dir, "mailboxes", "meta.json.tmp-9")} {
		if _, err := os.Stat(gone); !os.IsNotExist(err) {
			t.Errorf("%s is still there", gone)
		}
	}
	matches, _ := filepath.Glob(filepath.Join(h.dir, "mailboxes", id, "*.tmp-*"))
	if len(matches) != 0 {
		t.Errorf("temporary files left: %v", matches)
	}
}

func TestSettings(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "mailbox.json")
	cfg, err := loadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Listen != "127.0.0.1:8787" || cfg.Data != filepath.Join(dir, "data") || cfg.MaxEnvelopeMB != 8 || cfg.RetentionDays != 30 {
		t.Errorf("defaults: %+v", cfg)
	}
	if _, err := os.Stat(path); err != nil {
		t.Error("the settings file was not written")
	}
	for _, bad := range []string{
		`{"listen": "0.0.0.0:8787"}`, `{"listen": "192.168.1.5:8787"}`, `{"listen": "127.0.0.1:0"}`, `{"listen": "localhost"}`,
		`{"public_url": "http://example-laptop.example-tailnet.ts.net"}`, `{"max_envelope_mb": 0}`, `{"mailbox_quota_mb": 4}`, `{nope`,
	} {
		os.WriteFile(path, []byte(bad), 0o600)
		if _, err := loadConfig(path); err == nil {
			t.Errorf("%s was taken", bad)
		}
	}
	os.WriteFile(path, []byte(`{"listen": "[::1]:9000", "public_url": "https://example-laptop.example-tailnet.ts.net"}`), 0o600)
	if cfg, err := loadConfig(path); err != nil || cfg.Listen != "[::1]:9000" {
		t.Errorf("::1: %v %+v", err, cfg)
	}
}

// The first start makes the house token and the setup code once; it is not printed where the output is not a
// terminal, only where the PNG is; --setup-code makes the picture again with the same token; --new-token another.
func TestSetupCode(t *testing.T) {
	dir := t.TempDir()
	settings := filepath.Join(dir, "mailbox.json")
	os.WriteFile(settings, []byte(`{"public_url": "https://example-laptop.example-tailnet.ts.net"}`), 0o600)
	out, _ := os.Create(filepath.Join(dir, "out.txt"))
	if code := run([]string{"--config", settings, "--setup-code"}, out, out); code != 0 {
		t.Fatalf("--setup-code: %d", code)
	}
	out.Close()
	said, _ := os.ReadFile(filepath.Join(dir, "out.txt"))
	token, _ := os.ReadFile(filepath.Join(dir, "data", tokenFile))
	tok := strings.TrimSpace(string(token))
	if !validSecret(tok) {
		t.Fatalf("token %q", tok)
	}
	if strings.Contains(string(said), tok) || strings.Contains(string(said), "HMBOX") || strings.Contains(string(said), "█") {
		t.Errorf("printed where the output is not a terminal:\n%s", said)
	}
	if !strings.Contains(string(said), filepath.Join(dir, "data", setupPNG)) {
		t.Errorf("does not say where the PNG is:\n%s", said)
	}
	png1, err := os.ReadFile(filepath.Join(dir, "data", setupPNG))
	if err != nil || len(png1) < 100 {
		t.Fatalf("the PNG: %v", err)
	}
	var buf bytes.Buffer
	if err := writeSetup(&buf, true, filepath.Join(dir, "data"), tok, "https://example-laptop.example-tailnet.ts.net"); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "HMBOX:1:"+tok+":https://example-laptop.example-tailnet.ts.net") || !strings.Contains(buf.String(), "█") {
		t.Errorf("on a terminal it shows the code:\n%s", buf.String())
	}
	again, _ := os.Create(filepath.Join(dir, "again.txt"))
	run([]string{"--config", settings, "--setup-code"}, again, again)
	again.Close()
	if same, _ := os.ReadFile(filepath.Join(dir, "data", tokenFile)); string(same) != string(token) {
		t.Error("--setup-code changed the token")
	}
	renew, _ := os.Create(filepath.Join(dir, "renew.txt"))
	run([]string{"--config", settings, "--new-token"}, renew, renew)
	renew.Close()
	if other, _ := os.ReadFile(filepath.Join(dir, "data", tokenFile)); string(other) == string(token) {
		t.Error("--new-token kept the token")
	}
	v, _ := os.Create(filepath.Join(dir, "v.txt"))
	run([]string{"--version"}, v, v)
	v.Close()
	if said, _ := os.ReadFile(filepath.Join(dir, "v.txt")); string(said) != "housemove-mailbox "+version+"\n" {
		t.Errorf("--version said %q", said)
	}
	// Without an address there is no code, and it says what to do.
	var none bytes.Buffer
	writeSetup(&none, true, filepath.Join(dir, "data"), tok, "")
	if strings.Contains(none.String(), tok) || !strings.Contains(none.String(), "public_url") {
		t.Errorf("with no address:\n%s", none.String())
	}
}

// Many phones at once: every envelope kept, counted and listed once.
func TestManyAtOnce(t *testing.T) {
	h := newHarness(t, func(c *config) { c.RatePerMinute = 100000 })
	id, secret := randomHex(32), randomHex(64)
	h.register(id, secret)
	var wg sync.WaitGroup
	for i := 0; i < 40; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			req, _ := http.NewRequest("POST", h.http.URL+"/v1/mailboxes/"+id+"/envelopes", bytes.NewReader([]byte(fmt.Sprintf("envelope %d", i))))
			resp, err := http.DefaultClient.Do(req)
			if err == nil {
				resp.Body.Close()
			}
		}(i)
	}
	wg.Wait()
	_, list := h.list(id, secret)
	if len(list) != 40 {
		t.Errorf("%d envelopes kept", len(list))
	}
	if _, e, _ := h.st.counts(); e != 40 {
		t.Errorf("%d counted", e)
	}
}
