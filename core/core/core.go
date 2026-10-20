// Package core is the gomobile-facing surface of the tunnel core.
// Only strings, ints, errors and callback interfaces cross this boundary.
package core

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"sync"
	"time"

	"scry/core/tstunnel"
	"scry/core/tunnel"
	"scry/core/wgconfig"
)

// Tunnel states reported in Status().
const (
	StateStopped    = "stopped"
	StateConnecting = "connecting"
	// StateUp means the userspace device is running. A WireGuard handshake only
	// happens once traffic flows, so lastHandshake stays 0 until the first dial.
	StateUp    = "up"
	StateError = "error"
)

// StatusListener receives a JSON status document on every state change.
type StatusListener interface {
	OnStatus(statusJSON string)
}

// Config is the JSON document handed over by the shell.
type Config struct {
	Kind      string           `json:"kind"` // "wireguard", "tailscale", or "direct" (no tunnel)
	WireGuard *wgconfig.Config `json:"wireguard,omitempty"`
	Tailscale *tstunnel.Config `json:"tailscale,omitempty"`

	// StateDir is where an embedded node keeps its identity; set by Acquire, never by the caller.
	StateDir string `json:"-"`
}

type runner interface {
	DialContext(ctx context.Context, network, addr string) (net.Conn, error)
	Close()
	LastHandshake() time.Time
}

// directRunner reaches the server over the device's own network, with no tunnel. It is
// used when the server answers directly (for example on the home network), so the VPN
// is not needed. It starts instantly and has nothing to tear down.
type directRunner struct{}

func (directRunner) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, network, addr)
}
func (directRunner) Close()                   {}
func (directRunner) LastHandshake() time.Time { return time.Time{} }

// startFn is swapped out in tests.
var startFn = func(c *Config) (runner, error) {
	switch c.Kind {
	case "direct":
		return directRunner{}, nil
	case "tailscale":
		if c.Tailscale == nil {
			return nil, fmt.Errorf("missing tailscale section")
		}
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()
		return tstunnel.Start(ctx, c.Tailscale, c.StateDir)
	case "wireguard":
		if c.WireGuard == nil {
			return nil, fmt.Errorf("missing wireguard section")
		}
		return tunnel.Start(c.WireGuard)
	default:
		return nil, fmt.Errorf("unsupported tunnel kind %q", c.Kind)
	}
}

type manager struct {
	mu       sync.Mutex
	leases   map[string]struct{}
	cfgJSON  string
	cfg      *Config
	run      runner
	state    string
	errMsg   string
	gen      int // bumped on every start/stop so stale goroutines can tell
	grace    time.Duration
	timer    *time.Timer
	listener StatusListener
}

var m = &manager{leases: map[string]struct{}{}, state: StateStopped, grace: 20 * time.Second}

// ParseWireGuardConf converts wg-quick text into the config JSON that Acquire expects.
func ParseWireGuardConf(text string) (string, error) {
	c, err := wgconfig.Parse(text)
	if err != nil {
		return "", err
	}
	b, err := json.Marshal(&Config{Kind: "wireguard", WireGuard: c})
	return string(b), err
}

// NetworkChanged tells the running tunnel that the device's network changed, so it can
// rebind its sockets and re-handshake. It does nothing when no tunnel is running or for
// a direct connection.
func NetworkChanged() {
	m.mu.Lock()
	r := m.run
	m.mu.Unlock()
	if rb, ok := r.(interface{ Rebind() }); ok {
		rb.Rebind()
	}
}

// SetGraceSeconds sets how long the tunnel stays up after the last lease is released.
func SetGraceSeconds(s int) {
	m.mu.Lock()
	m.grace = time.Duration(s) * time.Second
	m.mu.Unlock()
}

// ValidateConfig checks a config document without starting anything, returning the same
// errors Acquire would. stateDir is the app's core state directory.
func ValidateConfig(configJSON string, stateDir string) error {
	var cfg Config
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("invalid config JSON")
	}
	return validate(&cfg, stateDir)
}

// HasTailscaleState reports whether this device already holds a Tailscale identity, in which
// case no auth key is needed to connect.
func HasTailscaleState(stateDir string) bool { return tstunnel.HasState(stateDir) }

func validate(cfg *Config, stateDir string) error {
	switch cfg.Kind {
	case "tailscale":
		if cfg.Tailscale == nil {
			return fmt.Errorf("missing tailscale section")
		}
		return cfg.Tailscale.Validate(stateDir)
	case "wireguard":
		if cfg.WireGuard == nil {
			return fmt.Errorf("missing wireguard section")
		}
		return cfg.WireGuard.Validate()
	case "direct":
		return nil
	}
	return fmt.Errorf("unsupported tunnel kind %q", cfg.Kind)
}

// Acquire takes a lease and starts the tunnel if needed. It returns as soon as
// the config is validated; use Await or the status listener to see when it is up.
// Passing a different config than the running one restarts the tunnel; existing
// leases stay valid. Safe to call repeatedly and from any thread.
func Acquire(configJSON string, stateDir string) (string, error) {
	var cfg Config
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return "", fmt.Errorf("invalid config JSON")
	}
	cfg.StateDir = stateDir
	if err := validate(&cfg, stateDir); err != nil {
		return "", err
	}
	if err := ensureProxy(stateDir); err != nil {
		return "", err
	}
	id, err := newID()
	if err != nil {
		return "", err
	}

	m.mu.Lock()
	m.leases[id] = struct{}{}
	if m.timer != nil {
		m.timer.Stop()
		m.timer = nil
	}
	// A different config replaces the running tunnel, and also one that is still connecting
	// (its goroutine notices the bumped generation and closes what it built).
	restart := (m.run != nil || m.state == StateConnecting) && m.cfgJSON != configJSON
	if restart {
		m.stopLocked()
	}
	if m.run == nil && m.state != StateConnecting {
		m.cfgJSON, m.cfg = configJSON, &cfg
		m.startLocked()
	}
	snap := m.snapshotLocked()
	l := m.listener
	m.mu.Unlock()
	notify(l, snap)
	return id, nil
}

// Release drops a lease. When the last one goes, the tunnel stops after the grace period.
func Release(leaseID string) {
	m.mu.Lock()
	if _, ok := m.leases[leaseID]; !ok {
		m.mu.Unlock()
		return
	}
	delete(m.leases, leaseID)
	if len(m.leases) == 0 && m.state != StateStopped {
		gen := m.gen
		if m.timer != nil {
			m.timer.Stop()
		}
		m.timer = time.AfterFunc(m.grace, func() { m.graceExpired(gen) })
	}
	m.mu.Unlock()
}

func (m *manager) graceExpired(gen int) {
	m.mu.Lock()
	if len(m.leases) != 0 || m.gen != gen {
		m.mu.Unlock()
		return
	}
	m.stopLocked()
	snap := m.snapshotLocked()
	l := m.listener
	m.mu.Unlock()
	notify(l, snap)
}

// Await blocks until the tunnel is up, failed, or the timeout passes.
func Await(timeoutMillis int) error {
	deadline := time.Now().Add(time.Duration(timeoutMillis) * time.Millisecond)
	for {
		m.mu.Lock()
		state, msg := m.state, m.errMsg
		m.mu.Unlock()
		switch state {
		case StateUp:
			return nil
		case StateError:
			return fmt.Errorf("%s", msg)
		case StateStopped:
			return fmt.Errorf("tunnel is not running")
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("timed out waiting for tunnel")
		}
		time.Sleep(50 * time.Millisecond)
	}
}

// Status returns the current status as JSON.
func Status() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.snapshotLocked()
}

func SetStatusListener(l StatusListener) {
	m.mu.Lock()
	m.listener = l
	m.mu.Unlock()
}

// startLocked begins connecting in the background. Caller holds m.mu.
func (m *manager) startLocked() {
	m.gen++
	gen, cfg := m.gen, m.cfg
	m.state, m.errMsg = StateConnecting, ""
	go func() {
		r, err := startFn(cfg)
		m.mu.Lock()
		if m.gen != gen { // stopped or restarted while connecting
			m.mu.Unlock()
			if r != nil {
				r.Close()
			}
			return
		}
		if err != nil {
			m.state, m.errMsg = StateError, err.Error()
		} else {
			m.run, m.state = r, StateUp
		}
		snap := m.snapshotLocked()
		l := m.listener
		m.mu.Unlock()
		notify(l, snap)
	}()
}

// stopLocked tears the tunnel down. Caller holds m.mu.
func (m *manager) stopLocked() {
	m.gen++
	if m.run != nil {
		m.run.Close()
		m.run = nil
	}
	m.state, m.errMsg = StateStopped, ""
}

func (m *manager) snapshotLocked() string {
	var hs int64
	if m.run != nil {
		if t := m.run.LastHandshake(); !t.IsZero() {
			hs = t.Unix()
		}
	}
	kind := ""
	if m.cfg != nil {
		kind = m.cfg.Kind
	}
	b, _ := json.Marshal(map[string]any{
		"state":         m.state,
		"kind":          kind,
		"lastHandshake": hs,
		"error":         m.errMsg,
		"leases":        len(m.leases),
	})
	return string(b)
}

func notify(l StatusListener, snap string) {
	if l != nil {
		l.OnStatus(snap)
	}
}

func newID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}
