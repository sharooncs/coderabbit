// Command housemove-mailbox is HouseMove's internet mailbox (moving-app/design/encryption.md, "Mailbox as built"): a
// small program on an always-on computer that keeps sealed envelopes for the phones of a move until each phone fetches
// its own. It listens on 127.0.0.1 only; Tailscale Funnel gives it a fixed https address with a normal certificate. It
// only ever stores and forwards sealed bytes: it holds no key that opens them, and never looks inside.
//
//	housemove-mailbox                 run (settings: mailbox.json beside the program, made at the first start)
//	housemove-mailbox --config FILE   run with another settings file
//	housemove-mailbox --setup-code    write the setup code's QR again (setup-code.png), and stop
//	housemove-mailbox --new-token     make a new house token (and its setup code), and stop
//	housemove-mailbox --version       say the version, and stop
//
// README.md says how it is set up, the API, the limits, and how the .exe is built.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"sync"
	"time"
)

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

func defaultConfigPath() string {
	exe, err := os.Executable()
	if err != nil {
		return "mailbox.json"
	}
	return filepath.Join(filepath.Dir(exe), "mailbox.json")
}

func run(args []string, stdout, stderr *os.File) int {
	fs := flag.NewFlagSet("housemove-mailbox", flag.ContinueOnError)
	fs.SetOutput(stderr)
	configPath := fs.String("config", defaultConfigPath(), "the settings file")
	showVersion := fs.Bool("version", false, "say the version, and stop")
	setup := fs.Bool("setup-code", false, "write the setup code's QR again, and stop")
	renew := fs.Bool("new-token", false, "make a new house token and its setup code, and stop")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *showVersion {
		fmt.Fprintf(stdout, "housemove-mailbox %s\n", version)
		return 0
	}
	logger := log.New(stderr, "", log.LstdFlags)
	cfg, err := loadConfig(*configPath)
	if err != nil {
		logger.Print(err)
		return 1
	}
	if err := os.MkdirAll(cfg.Data, 0o700); err != nil {
		logger.Printf("the data folder %s: %v", cfg.Data, err)
		return 1
	}
	publicURL := cfg.PublicURL
	if publicURL == "" {
		publicURL = tailscaleURL()
	}
	terminal := isTerminal(stdout)
	if *renew {
		token, err := newToken(cfg.Data)
		if err != nil {
			logger.Printf("could not make a new house token: %v", err)
			return 1
		}
		fmt.Fprintln(stdout, "A new house token is made. Mailboxes registered already keep working; registering another takes the new setup code.")
		if err := writeSetup(stdout, terminal, cfg.Data, token, publicURL); err != nil {
			logger.Print(err)
			return 1
		}
		return 0
	}
	token, made, err := loadToken(cfg.Data)
	if err != nil {
		logger.Print(err)
		return 1
	}
	if *setup || made {
		if made {
			fmt.Fprintln(stdout, "First start: a house token is made, and shown once in the setup code below.")
		}
		if err := writeSetup(stdout, terminal, cfg.Data, token, publicURL); err != nil {
			logger.Print(err)
			return 1
		}
		if *setup {
			return 0
		}
	}

	st, err := openStore(cfg.Data, cfg, time.Now)
	if err != nil {
		logger.Printf("the data folder %s: %v", cfg.Data, err)
		return 1
	}
	hash := secretHash(token)
	srv := newServer(cfg, st, func() []byte { return hash }, logger, time.Now)
	h := httpServer(cfg.Listen, srv.routes(), logger)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		janitor(ctx, st, logger)
	}()
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		h.Shutdown(shutdown)
	}()
	m, e, b := st.counts()
	logger.Printf("housemove-mailbox %s listening on %s; %d mailboxes, %d envelopes, %d bytes", version, cfg.Listen, m, e, b)
	err = h.ListenAndServe()
	stop()
	wg.Wait()
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		logger.Print(err)
		return 1
	}
	return 0
}

// janitor deletes old envelopes and idle mailboxes at the start and every hour.
func janitor(ctx context.Context, st *store, logger *log.Logger) {
	tick := time.NewTicker(time.Hour)
	defer tick.Stop()
	for {
		if e, m := st.sweep(); e > 0 || m > 0 {
			logger.Printf("deleted %d old envelopes and %d mailboxes not read for long", e, m)
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}
