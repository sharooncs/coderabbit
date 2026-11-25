package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// The mailboxes, as plain files under the data folder:
//
//	mailboxes/<id>/meta.json        {"secret_sha256": …, "created": …, "last_read": …} (milliseconds)
//	mailboxes/<id>/env/<envelope>   an envelope's bytes, as they came
//
// Every file is written whole to a temporary file beside it, flushed to disk, then renamed over its name, so a crash or
// a power cut leaves the old file or the new one, never half of one; leftovers of a write cut short are deleted at start.
// Only the hash of each read secret is kept. Envelope IDs are the time they came (nanoseconds) and four random bytes,
// in hex, so they sort by time. Nothing reads what is in an envelope.

var (
	errNoMailbox  = errors.New("no such mailbox")
	errForbidden  = errors.New("wrong secret")
	errExists     = errors.New("that mailbox is registered with another secret")
	errNoRoom     = errors.New("no room for another mailbox")
	errFull       = errors.New("the mailbox, or the mailboxes together, would hold too much")
	errNoEnvelope = errors.New("no such envelope")
)

type meta struct {
	SecretSHA256 string `json:"secret_sha256"`
	Created      int64  `json:"created"`
	LastRead     int64  `json:"last_read"`
}

type box struct {
	meta  meta
	hash  []byte
	used  int64
	count int
}

type listed struct {
	ID   string `json:"id"`
	Size int64  `json:"size"`
	At   int64  `json:"at"`
}

type store struct {
	mu    sync.Mutex
	dir   string
	cfg   config
	now   func() time.Time
	boxes map[string]*box
	total int64
}

func isHex(s string, n int) bool {
	if len(s) != n {
		return false
	}
	for _, c := range s {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

func validMailboxID(id string) bool { return isHex(id, 32) }
func validSecret(s string) bool     { return isHex(s, 64) }
func validEnvelopeID(e string) bool { return isHex(e, 24) }

func secretHash(s string) []byte {
	h := sha256.Sum256([]byte(s))
	return h[:]
}

// writeFileAtomic writes data to path through a temporary file in the same folder, flushed before it is renamed.
func writeFileAtomic(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, filepath.Base(path)+".tmp-*")
	if err != nil {
		return err
	}
	tmp := f.Name()
	_, err = f.Write(data)
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Chmod(tmp, 0o600)
	}
	if err == nil {
		err = os.Rename(tmp, path)
	}
	if err != nil {
		os.Remove(tmp)
	}
	return err
}

// openStore reads what the data folder holds, and deletes what a cut-short write left behind.
func openStore(dataDir string, cfg config, now func() time.Time) (*store, error) {
	s := &store{dir: filepath.Join(dataDir, "mailboxes"), cfg: cfg, now: now, boxes: map[string]*box{}}
	if err := os.MkdirAll(s.dir, 0o700); err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return nil, err
	}
	for _, e := range entries {
		if strings.Contains(e.Name(), ".tmp-") {
			os.RemoveAll(filepath.Join(s.dir, e.Name()))
			continue
		}
		if !e.IsDir() || !validMailboxID(e.Name()) {
			continue
		}
		b, err := s.load(e.Name())
		if err != nil {
			// A mailbox whose meta.json cannot be read cannot be read by its phone either: it registers again.
			os.RemoveAll(filepath.Join(s.dir, e.Name()))
			continue
		}
		s.boxes[e.Name()] = b
		s.total += b.used
	}
	return s, nil
}

func (s *store) load(id string) (*box, error) {
	raw, err := os.ReadFile(filepath.Join(s.dir, id, "meta.json"))
	if err != nil {
		return nil, err
	}
	var m meta
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, err
	}
	h, err := hex.DecodeString(m.SecretSHA256)
	if err != nil || len(h) != sha256.Size {
		return nil, errors.New("bad meta.json")
	}
	b := &box{meta: m, hash: h}
	envs, _ := os.ReadDir(filepath.Join(s.dir, id, "env"))
	for _, e := range envs {
		p := filepath.Join(s.dir, id, "env", e.Name())
		if strings.Contains(e.Name(), ".tmp-") || !validEnvelopeID(e.Name()) {
			os.Remove(p)
			continue
		}
		if info, err := e.Info(); err == nil {
			b.used += info.Size()
			b.count++
		}
	}
	return b, nil
}

func (s *store) millis() int64 { return s.now().UnixMilli() }

func (s *store) writeMeta(id string, m meta) error {
	out, _ := json.Marshal(m)
	return writeFileAtomic(filepath.Join(s.dir, id, "meta.json"), out)
}

// register makes the mailbox id, read with secret. Again with the same secret it changes nothing (a phone whose answer
// was lost asks again); with another, errExists.
func (s *store) register(id, secret string) (created bool, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	h := secretHash(secret)
	if b, ok := s.boxes[id]; ok {
		if subtle.ConstantTimeCompare(b.hash, h) == 1 {
			return false, nil
		}
		return false, errExists
	}
	if len(s.boxes) >= s.cfg.MaxMailboxes {
		return false, errNoRoom
	}
	now := s.millis()
	m := meta{SecretSHA256: hex.EncodeToString(h), Created: now, LastRead: now}
	if err := os.MkdirAll(filepath.Join(s.dir, id, "env"), 0o700); err != nil {
		return false, err
	}
	if err := s.writeMeta(id, m); err != nil {
		os.RemoveAll(filepath.Join(s.dir, id))
		return false, err
	}
	s.boxes[id] = &box{meta: m, hash: h}
	return true, nil
}

// check finds mailbox id and checks secret against it, taking as long whatever the secret is. Call holding mu.
func (s *store) check(id, secret string) (*box, error) {
	b, ok := s.boxes[id]
	if !ok {
		return nil, errNoMailbox
	}
	if subtle.ConstantTimeCompare(b.hash, secretHash(secret)) != 1 {
		return nil, errForbidden
	}
	return b, nil
}

// unregister deletes mailbox id and its envelopes.
func (s *store) unregister(id, secret string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, err := s.check(id, secret)
	if err != nil {
		return err
	}
	s.drop(id, b)
	return nil
}

func (s *store) drop(id string, b *box) {
	os.RemoveAll(filepath.Join(s.dir, id))
	s.total -= b.used
	delete(s.boxes, id)
}

func newEnvelopeID(t time.Time) string {
	var r [4]byte
	rand.Read(r[:])
	return fmt.Sprintf("%016x%08x", uint64(t.UnixNano()), binary.BigEndian.Uint32(r[:]))
}

// post keeps body as a new envelope in mailbox id, within the quotas.
func (s *store) post(id string, body []byte) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, ok := s.boxes[id]
	if !ok {
		return "", errNoMailbox
	}
	n := int64(len(body))
	if b.used+n > s.cfg.mailboxQuota() || s.total+n > s.cfg.totalQuota() {
		return "", errFull
	}
	var eid, path string
	for {
		eid = newEnvelopeID(s.now())
		path = filepath.Join(s.dir, id, "env", eid)
		if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
			break
		}
	}
	if err := writeFileAtomic(path, body); err != nil {
		return "", err
	}
	b.used += n
	b.count++
	s.total += n
	return eid, nil
}

// list gives mailbox id's envelopes, oldest first, and notes that its phone read it.
func (s *store) list(id, secret string) ([]listed, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, err := s.check(id, secret)
	if err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(filepath.Join(s.dir, id, "env"))
	if err != nil {
		return nil, err
	}
	out := []listed{}
	for _, e := range entries {
		if !validEnvelopeID(e.Name()) {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		nanos, _ := strconv.ParseUint(e.Name()[:16], 16, 64)
		out = append(out, listed{ID: e.Name(), Size: info.Size(), At: int64(nanos / 1e6)})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	// Being read keeps a mailbox; the time is written at most once an hour.
	now := s.millis()
	if now-b.meta.LastRead > 3_600_000 {
		m := b.meta
		m.LastRead = now
		if s.writeMeta(id, m) == nil {
			b.meta = m
		}
	}
	return out, nil
}

// fetch gives the bytes of envelope eid in mailbox id.
func (s *store) fetch(id, secret, eid string) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := s.check(id, secret); err != nil {
		return nil, err
	}
	data, err := os.ReadFile(filepath.Join(s.dir, id, "env", eid))
	if errors.Is(err, os.ErrNotExist) {
		return nil, errNoEnvelope
	}
	return data, err
}

// remove deletes envelope eid from mailbox id: its phone has it.
func (s *store) remove(id, secret, eid string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, err := s.check(id, secret)
	if err != nil {
		return err
	}
	return s.removeEnvelope(id, b, eid)
}

func (s *store) removeEnvelope(id string, b *box, eid string) error {
	p := filepath.Join(s.dir, id, "env", eid)
	info, err := os.Stat(p)
	if errors.Is(err, os.ErrNotExist) {
		return errNoEnvelope
	}
	if err != nil {
		return err
	}
	if err := os.Remove(p); err != nil {
		return err
	}
	b.used -= info.Size()
	b.count--
	s.total -= info.Size()
	return nil
}

// sweep deletes envelopes older than the retention, and mailboxes not read for IdleDays. It says how many of each.
func (s *store) sweep() (envelopes, mailboxes int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	oldest := uint64(now.Add(-time.Duration(s.cfg.RetentionDays) * 24 * time.Hour).UnixNano())
	idle := now.Add(-time.Duration(s.cfg.IdleDays) * 24 * time.Hour).UnixMilli()
	for id, b := range s.boxes {
		if b.meta.LastRead < idle {
			s.drop(id, b)
			mailboxes++
			continue
		}
		entries, _ := os.ReadDir(filepath.Join(s.dir, id, "env"))
		for _, e := range entries {
			if !validEnvelopeID(e.Name()) {
				continue
			}
			nanos, err := strconv.ParseUint(e.Name()[:16], 16, 64)
			if err == nil && nanos < oldest {
				if s.removeEnvelope(id, b, e.Name()) == nil {
					envelopes++
				}
			}
		}
	}
	return
}

// counts says how many mailboxes and envelopes there are and how many bytes they hold, for the log.
func (s *store) counts() (mailboxes, envelopes int, bytes int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, b := range s.boxes {
		envelopes += b.count
	}
	return len(s.boxes), envelopes, s.total
}
