package main

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

// version is what --version and the health answer say.
const version = "1.0.0"

// server answers the phones (README.md, "The API"). It never looks inside an envelope, and its log says only the
// method, the kind of request, the answer's status and size and how long it took: no mailbox or envelope ID, no token,
// no secret, no address, nothing of what an envelope holds.
type server struct {
	cfg           config
	st            *store
	tokenHash     func() []byte
	general       *limiter
	registrations *limiter
	failures      *limiter
	log           *log.Logger
}

func newServer(cfg config, st *store, tokenHash func() []byte, logger *log.Logger, now func() time.Time) *server {
	return &server{
		cfg:           cfg,
		st:            st,
		tokenHash:     tokenHash,
		general:       newLimiter(cfg.RatePerMinute, time.Minute, max(cfg.RatePerMinute/2, 1), now),
		registrations: newLimiter(cfg.RegistrationsPerHour, time.Hour, cfg.RegistrationsPerHour, now),
		failures:      newLimiter(cfg.FailuresPerHour, time.Hour, cfg.FailuresPerHour, now),
		log:           logger,
	}
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/health", s.health)
	mux.HandleFunc("PUT /v1/mailboxes/{id}", s.register)
	mux.HandleFunc("DELETE /v1/mailboxes/{id}", s.unregister)
	mux.HandleFunc("POST /v1/mailboxes/{id}/envelopes", s.post)
	mux.HandleFunc("GET /v1/mailboxes/{id}/envelopes", s.list)
	mux.HandleFunc("GET /v1/mailboxes/{id}/envelopes/{eid}", s.fetch)
	mux.HandleFunc("DELETE /v1/mailboxes/{id}/envelopes/{eid}", s.remove)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { answer(w, http.StatusNotFound, "not found") })
	return s.wrap(mux)
}

// recorder keeps the status and size of an answer, for the log.
type recorder struct {
	http.ResponseWriter
	status int
	size   int64
}

func (r *recorder) WriteHeader(code int) {
	if r.status == 0 {
		r.status = code
	}
	r.ResponseWriter.WriteHeader(code)
}

func (r *recorder) Write(b []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	n, err := r.ResponseWriter.Write(b)
	r.size += int64(n)
	return n, err
}

// route is the kind of request, for the log: the path with the IDs left out.
func route(path string) string {
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) >= 3 && parts[0] == "v1" && parts[1] == "mailboxes" {
		parts[2] = "{id}"
		if len(parts) >= 5 && parts[3] == "envelopes" {
			parts[4] = "{envelope}"
		}
	}
	if len(parts) > 5 {
		parts = parts[:5]
	}
	return "/" + strings.Join(parts, "/")
}

func (s *server) wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &recorder{ResponseWriter: w}
		h := rec.Header()
		h.Set("Cache-Control", "no-store")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		defer func() {
			if p := recover(); p != nil {
				answer(rec, http.StatusInternalServerError, "the mailbox had a problem")
			}
			s.log.Printf("%s %s %d %dB %dms", r.Method, route(r.URL.Path), rec.status, rec.size, time.Since(start).Milliseconds())
		}()
		who := clientAddress(r, s.cfg.TrustForwardedFor)
		if s.failures.spent(who) || !s.general.allow(who) {
			answer(rec, http.StatusTooManyRequests, "too many requests: try again later")
			return
		}
		next.ServeHTTP(rec, r)
	})
}

func answer(w http.ResponseWriter, status int, message string) {
	answerJSON(w, status, map[string]any{"error": message})
}

func answerJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func bearer(r *http.Request) string {
	v, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok {
		return ""
	}
	return strings.TrimSpace(v)
}

// failed notes a wrong token or secret from r's sender: too many, and it is turned away for a while.
func (s *server) failed(r *http.Request) {
	s.failures.allow(clientAddress(r, s.cfg.TrustForwardedFor))
}

func (s *server) health(w http.ResponseWriter, r *http.Request) {
	answerJSON(w, http.StatusOK, map[string]any{"ok": true, "name": "housemove-mailbox", "version": version})
}

func (s *server) register(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validMailboxID(id) {
		answer(w, http.StatusBadRequest, "a mailbox ID is 32 lower-case hex digits")
		return
	}
	presented := secretHash(bearer(r))
	if subtle.ConstantTimeCompare(presented, s.tokenHash()) != 1 {
		s.failed(r)
		answer(w, http.StatusUnauthorized, "registering a mailbox needs the house token")
		return
	}
	if !s.registrations.allow(clientAddress(r, s.cfg.TrustForwardedFor)) {
		answer(w, http.StatusTooManyRequests, "too many mailboxes registered: try again later")
		return
	}
	var body struct {
		Secret string `json:"secret"`
	}
	raw, err := io.ReadAll(io.LimitReader(r.Body, 4096))
	if err != nil || json.Unmarshal(raw, &body) != nil || !validSecret(body.Secret) {
		answer(w, http.StatusBadRequest, "the body is {\"secret\": 64 lower-case hex digits}")
		return
	}
	created, err := s.st.register(id, body.Secret)
	switch {
	case errors.Is(err, errExists):
		answer(w, http.StatusConflict, "that mailbox is registered with another secret")
	case errors.Is(err, errNoRoom):
		answer(w, http.StatusInsufficientStorage, "no room for another mailbox")
	case err != nil:
		answer(w, http.StatusInternalServerError, "the mailbox could not be registered")
	case created:
		answerJSON(w, http.StatusCreated, map[string]any{"id": id})
	default:
		answerJSON(w, http.StatusOK, map[string]any{"id": id})
	}
}

// readable answers a request that needs a mailbox's read secret: the error to answer, or nil.
func (s *server) readError(w http.ResponseWriter, r *http.Request, err error) bool {
	switch {
	case err == nil:
		return false
	case errors.Is(err, errNoMailbox):
		answer(w, http.StatusNotFound, "no such mailbox")
	case errors.Is(err, errForbidden):
		s.failed(r)
		answer(w, http.StatusForbidden, "wrong secret")
	case errors.Is(err, errNoEnvelope):
		answer(w, http.StatusNotFound, "no such envelope")
	default:
		answer(w, http.StatusInternalServerError, "the mailbox had a problem")
	}
	return true
}

func (s *server) ids(w http.ResponseWriter, r *http.Request, withEnvelope bool) (string, string, bool) {
	id, eid := r.PathValue("id"), r.PathValue("eid")
	if !validMailboxID(id) || (withEnvelope && !validEnvelopeID(eid)) {
		answer(w, http.StatusBadRequest, "not a mailbox or envelope ID")
		return "", "", false
	}
	return id, eid, true
}

func (s *server) unregister(w http.ResponseWriter, r *http.Request) {
	id, _, ok := s.ids(w, r, false)
	if !ok {
		return
	}
	if s.readError(w, r, s.st.unregister(id, bearer(r))) {
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) post(w http.ResponseWriter, r *http.Request) {
	id, _, ok := s.ids(w, r, false)
	if !ok {
		return
	}
	limit := s.cfg.maxEnvelope()
	if r.ContentLength > limit {
		answer(w, http.StatusRequestEntityTooLarge, "that envelope is larger than the mailbox takes")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, limit))
	var tooLarge *http.MaxBytesError
	switch {
	case errors.As(err, &tooLarge):
		answer(w, http.StatusRequestEntityTooLarge, "that envelope is larger than the mailbox takes")
		return
	case err != nil:
		answer(w, http.StatusBadRequest, "the envelope did not come whole")
		return
	case len(body) == 0:
		answer(w, http.StatusBadRequest, "an envelope is not empty")
		return
	}
	eid, err := s.st.post(id, body)
	switch {
	case errors.Is(err, errNoMailbox):
		answer(w, http.StatusNotFound, "no such mailbox")
	case errors.Is(err, errFull):
		answer(w, http.StatusInsufficientStorage, "the mailbox is full")
	case err != nil:
		answer(w, http.StatusInternalServerError, "the envelope could not be kept")
	default:
		answerJSON(w, http.StatusCreated, map[string]any{"id": eid})
	}
}

func (s *server) list(w http.ResponseWriter, r *http.Request) {
	id, _, ok := s.ids(w, r, false)
	if !ok {
		return
	}
	out, err := s.st.list(id, bearer(r))
	if s.readError(w, r, err) {
		return
	}
	answerJSON(w, http.StatusOK, map[string]any{"envelopes": out})
}

func (s *server) fetch(w http.ResponseWriter, r *http.Request) {
	id, eid, ok := s.ids(w, r, true)
	if !ok {
		return
	}
	data, err := s.st.fetch(id, bearer(r), eid)
	if s.readError(w, r, err) {
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.WriteHeader(http.StatusOK)
	w.Write(data)
}

func (s *server) remove(w http.ResponseWriter, r *http.Request) {
	id, eid, ok := s.ids(w, r, true)
	if !ok {
		return
	}
	if s.readError(w, r, s.st.remove(id, bearer(r), eid)) {
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// httpServer is the server with time limits: headers within 10 s, a whole request (an 8 MB envelope on a slow phone)
// within 2 minutes.
func httpServer(addr string, h http.Handler, logger *log.Logger) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           h,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       2 * time.Minute,
		WriteTimeout:      2 * time.Minute,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
		ErrorLog:          logger,
	}
}
