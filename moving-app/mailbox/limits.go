package main

import (
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// limiter is a token bucket per sender's address: up to burst requests at once, refilled at rate per period. Buckets
// that are full again are forgotten now and then, so the map does not grow without end.
type limiter struct {
	mu      sync.Mutex
	rate    float64 // tokens a second
	burst   float64
	now     func() time.Time
	buckets map[string]*bucket
	swept   time.Time
}

type bucket struct {
	tokens float64
	at     time.Time
}

func newLimiter(perPeriod int, period time.Duration, burst int, now func() time.Time) *limiter {
	return &limiter{rate: float64(perPeriod) / period.Seconds(), burst: float64(burst), now: now, buckets: map[string]*bucket{}}
}

// allow takes one token from key's bucket, if there is one.
func (l *limiter) allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	if now.Sub(l.swept) > 10*time.Minute {
		for k, b := range l.buckets {
			if b.tokens+now.Sub(b.at).Seconds()*l.rate >= l.burst {
				delete(l.buckets, k)
			}
		}
		l.swept = now
	}
	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{tokens: l.burst, at: now}
		l.buckets[key] = b
	}
	b.tokens += now.Sub(b.at).Seconds() * l.rate
	if b.tokens > l.burst {
		b.tokens = l.burst
	}
	b.at = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// spent says whether key's bucket is empty, without taking a token.
func (l *limiter) spent(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	b, ok := l.buckets[key]
	if !ok {
		return false
	}
	tokens := b.tokens + l.now().Sub(b.at).Seconds()*l.rate
	return tokens < 1
}

// clientAddress is who sent r: the connection's address; for a connection from this machine (Tailscale Funnel's proxy)
// with trustForwarded, the last address in X-Forwarded-For, which that proxy adds.
func clientAddress(r *http.Request, trustForwarded bool) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip := net.ParseIP(host)
	if trustForwarded && ip != nil && ip.IsLoopback() {
		if fwd := r.Header.Values("X-Forwarded-For"); len(fwd) > 0 {
			parts := strings.Split(fwd[len(fwd)-1], ",")
			last := strings.TrimSpace(parts[len(parts)-1])
			if p := net.ParseIP(last); p != nil {
				return p.String()
			}
		}
	}
	if ip != nil {
		return ip.String()
	}
	return host
}
