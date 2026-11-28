package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// config is the mailbox's settings file (mailbox.json beside the program, or --config). A missing file is written with
// these defaults at the first start; a field left out keeps its default.
type config struct {
	// Listen is the address to listen on: 127.0.0.1 (or ::1) and a port. Tailscale Funnel passes the internet's requests on
	// to it; nothing else reaches it.
	Listen string `json:"listen"`
	// Data is the folder for the house token, the setup code and the mailboxes; relative to the settings file's folder.
	Data string `json:"data"`
	// PublicURL is the address phones use (https://<machine>.<tailnet>.ts.net); empty: asked of `tailscale status`.
	PublicURL string `json:"public_url"`
	// MaxEnvelopeMB is the largest envelope taken, in MB.
	MaxEnvelopeMB int `json:"max_envelope_mb"`
	// MailboxQuotaMB is the most one mailbox may hold, in MB.
	MailboxQuotaMB int `json:"mailbox_quota_mb"`
	// TotalQuotaMB is the most all mailboxes together may hold, in MB.
	TotalQuotaMB int `json:"total_quota_mb"`
	// MaxMailboxes is how many mailboxes may be registered at once.
	MaxMailboxes int `json:"max_mailboxes"`
	// RetentionDays: an envelope nobody has fetched and deleted is deleted after this many days.
	RetentionDays int `json:"retention_days"`
	// IdleDays: a mailbox not read for this many days is deleted, with its envelopes (its phone registers again).
	IdleDays int `json:"idle_days"`
	// RatePerMinute is how many requests one address may make in a minute (bursts of half as many at once).
	RatePerMinute int `json:"rate_per_minute"`
	// RegistrationsPerHour is how many mailboxes one address may register in an hour.
	RegistrationsPerHour int `json:"registrations_per_hour"`
	// FailuresPerHour is how many requests with a wrong token or secret one address may make in an hour.
	FailuresPerHour int `json:"failures_per_hour"`
	// TrustForwardedFor: requests come through Tailscale Funnel's proxy on this machine, which says who sent them in
	// X-Forwarded-For; its last entry is taken as the sender's address for the rate limits.
	TrustForwardedFor bool `json:"trust_forwarded_for"`
}

func defaultConfig() config {
	return config{
		Listen:               "127.0.0.1:8787",
		Data:                 "data",
		MaxEnvelopeMB:        8,
		MailboxQuotaMB:       64,
		TotalQuotaMB:         2048,
		MaxMailboxes:         200,
		RetentionDays:        30,
		IdleDays:             90,
		RatePerMinute:        120,
		RegistrationsPerHour: 20,
		FailuresPerHour:      30,
		TrustForwardedFor:    true,
	}
}

func (c config) maxEnvelope() int64  { return int64(c.MaxEnvelopeMB) << 20 }
func (c config) mailboxQuota() int64 { return int64(c.MailboxQuotaMB) << 20 }
func (c config) totalQuota() int64   { return int64(c.TotalQuotaMB) << 20 }

// loadConfig reads the settings file at path, writing one with the defaults when there is none. The data folder comes
// back as an absolute path.
func loadConfig(path string) (config, error) {
	c := defaultConfig()
	b, err := os.ReadFile(path)
	switch {
	case errors.Is(err, os.ErrNotExist):
		out, _ := json.MarshalIndent(c, "", "  ")
		if err := writeFileAtomic(path, append(out, '\n')); err != nil {
			return c, fmt.Errorf("could not write the settings file %s: %w", path, err)
		}
	case err != nil:
		return c, err
	default:
		if err := json.Unmarshal(b, &c); err != nil {
			return c, fmt.Errorf("the settings file %s is not valid JSON: %w", path, err)
		}
	}
	if err := c.check(); err != nil {
		return c, fmt.Errorf("the settings file %s: %w", path, err)
	}
	if !filepath.IsAbs(c.Data) {
		c.Data = filepath.Join(filepath.Dir(path), c.Data)
	}
	return c, nil
}

func (c config) check() error {
	host, port, err := net.SplitHostPort(c.Listen)
	if err != nil {
		return fmt.Errorf("listen %q is not host:port", c.Listen)
	}
	if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
		return fmt.Errorf("listen %q: the mailbox listens on 127.0.0.1 only (Tailscale Funnel reaches it there)", c.Listen)
	}
	if p, err := strconv.Atoi(port); err != nil || p < 1 || p > 65535 {
		return fmt.Errorf("listen %q: not a port", c.Listen)
	}
	if c.PublicURL != "" && !strings.HasPrefix(c.PublicURL, "https://") {
		return fmt.Errorf("public_url %q must begin with https://", c.PublicURL)
	}
	for name, v := range map[string]int{
		"max_envelope_mb": c.MaxEnvelopeMB, "mailbox_quota_mb": c.MailboxQuotaMB, "total_quota_mb": c.TotalQuotaMB,
		"max_mailboxes": c.MaxMailboxes, "retention_days": c.RetentionDays, "idle_days": c.IdleDays,
		"rate_per_minute": c.RatePerMinute, "registrations_per_hour": c.RegistrationsPerHour, "failures_per_hour": c.FailuresPerHour,
	} {
		if v < 1 {
			return fmt.Errorf("%s must be at least 1", name)
		}
	}
	if c.MailboxQuotaMB < c.MaxEnvelopeMB || c.TotalQuotaMB < c.MailboxQuotaMB {
		return errors.New("the quotas must be at least the largest envelope, and the total at least one mailbox's")
	}
	return nil
}
