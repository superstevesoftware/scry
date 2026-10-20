// Package tstunnel runs an embedded Tailscale node (tsnet) inside the app's process.
// Like the WireGuard tunnel it uses a userspace network stack, so it takes no system
// VPN slot and does not touch any other app's traffic.
package tstunnel

import (
	"context"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync/atomic"
	"time"

	"tailscale.com/tsnet"
)

// Debug turns on tsnet's verbose backend logging. Only set it in debug builds.
var Debug atomic.Bool

type Config struct {
	// AuthKey signs the node in the first time. It is ignored once state exists in the
	// state directory, so it can be a one-time key.
	AuthKey string `json:"authKey,omitempty"`
	// Hostname is the node name shown in the Tailscale admin console.
	Hostname string `json:"hostname"`
	// ControlURL is only for self-hosted coordination servers; empty means Tailscale.
	ControlURL string `json:"controlUrl,omitempty"`
	// Ephemeral nodes are removed by Tailscale shortly after they go offline.
	Ephemeral bool `json:"ephemeral,omitempty"`
}

var hostnameRE = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$`)

// stateFile is where tsnet keeps the node's identity inside its directory.
const stateFile = "tailscaled.state"

func stateDir(base string) string { return filepath.Join(base, "tailscale") }

// HasState reports whether this device has already signed in, so an auth key is optional.
func HasState(base string) bool {
	_, err := os.Stat(filepath.Join(stateDir(base), stateFile))
	return err == nil
}

// Validate checks the config; base is the app's state directory.
func (c *Config) Validate(base string) error {
	c.Hostname = strings.ToLower(strings.TrimSpace(c.Hostname))
	if c.Hostname == "" {
		c.Hostname = "scry"
	}
	if !hostnameRE.MatchString(c.Hostname) {
		return fmt.Errorf("node name may only contain letters, digits and hyphens")
	}
	if c.AuthKey == "" && !HasState(base) {
		return fmt.Errorf("an auth key is needed for the first sign-in")
	}
	if c.AuthKey != "" && !strings.HasPrefix(c.AuthKey, "tskey-") {
		return fmt.Errorf("that does not look like a Tailscale auth key (it should start with tskey-)")
	}
	if c.ControlURL != "" && !strings.HasPrefix(c.ControlURL, "https://") && !strings.HasPrefix(c.ControlURL, "http://") {
		return fmt.Errorf("control server must be an http(s) URL")
	}
	return nil
}

// setDefaultEnv sets an environment variable only if the system has not already set it.
func setDefaultEnv(k, v string) {
	if os.Getenv(k) == "" {
		os.Setenv(k, v)
	}
}

type Tunnel struct {
	srv *tsnet.Server
	up  time.Time
}

// Start signs in (or resumes the saved identity) and waits until the node is running.
// The auth key is never logged.
func Start(ctx context.Context, c *Config, base string) (*Tunnel, error) {
	if err := c.Validate(base); err != nil {
		return nil, err
	}
	dir := stateDir(base)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	// Do not upload client logs to Tailscale's log server; this app is meant to be self-contained.
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")

	// Android gives apps none of the usual environment (HOME, TMPDIR, XDG_*), and Tailscale's
	// logging code panics when it cannot find a writable place. Point everything at the app's
	// own private folder.
	setDefaultEnv("TS_LOGS_DIR", dir)
	tmp := filepath.Join(base, "tmp")
	cache := filepath.Join(base, "cache")
	for _, d := range []string{tmp, cache} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			return nil, err
		}
	}
	setDefaultEnv("TMPDIR", tmp)
	setDefaultEnv("HOME", base)
	setDefaultEnv("XDG_CACHE_HOME", cache)

	srv := &tsnet.Server{
		Dir:        dir,
		Hostname:   c.Hostname,
		AuthKey:    c.AuthKey,
		ControlURL: c.ControlURL,
		Ephemeral:  c.Ephemeral,
		UserLogf:   func(string, ...any) {}, // may carry the login URL; interactive login is not supported yet
	}
	if Debug.Load() {
		srv.Logf = func(format string, a ...any) { logf("tsnet: "+format, a...) }
	}
	if _, err := srv.Up(ctx); err != nil {
		srv.Close()
		return nil, fmt.Errorf("tailscale: %w", err)
	}
	return &Tunnel{srv: srv, up: time.Now()}, nil
}

func (t *Tunnel) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	return t.srv.Dial(ctx, network, addr)
}

func (t *Tunnel) Close() { t.srv.Close() }

// LastHandshake reports when the node came up; tsnet has no WireGuard-style handshake
// to expose, so this stands in for "the control connection is established".
func (t *Tunnel) LastHandshake() time.Time { return t.up }
