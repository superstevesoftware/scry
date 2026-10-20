package core

import (
	"context"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"

	"scry/core/proxy"
	"scry/core/tstunnel"
	"scry/core/tunnel"
)

// The loopback proxies outlive individual tunnel runs: they dial through
// whichever tunnel is currently up, so a config change does not move the UI port.
var lb struct {
	mu       sync.Mutex
	srv      *proxy.Server
	upstream *proxy.Upstream
}

func tunnelDial(ctx context.Context, network, addr string) (net.Conn, error) {
	m.mu.Lock()
	r := m.run
	m.mu.Unlock()
	if r == nil {
		return nil, fmt.Errorf("tunnel is not up")
	}
	return r.DialContext(ctx, network, addr)
}

// ensureProxy starts the loopback listeners once per process. The UI port and
// cookie value are persisted in stateDir so the WebView origin and login survive restarts.
func ensureProxy(stateDir string) error {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	if lb.srv != nil {
		return nil
	}
	if stateDir == "" {
		return fmt.Errorf("stateDir is required")
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return err
	}
	tokenFile := filepath.Join(stateDir, "ui_session_token")
	portFile := filepath.Join(stateDir, "ui_port")

	tok, err := readTrim(tokenFile)
	if err != nil || len(tok) < 32 {
		if tok, err = proxy.RandomToken(32); err != nil {
			return err
		}
		if err := os.WriteFile(tokenFile, []byte(tok), 0o600); err != nil {
			return err
		}
	}
	port := 0
	if s, err := readTrim(portFile); err == nil {
		port, _ = strconv.Atoi(s)
	}

	srv, err := proxy.New(tunnelDial, tok)
	if err != nil {
		return err
	}
	if lb.upstream != nil {
		srv.SetUpstream(lb.upstream)
	}
	// A taken persisted port is surfaced as an error rather than silently moved,
	// because a new port is a new origin and wipes the PWA's site data.
	if err := srv.Start(port); err != nil {
		return err
	}
	if port == 0 {
		if err := os.WriteFile(portFile, []byte(strconv.Itoa(srv.UIPort())), 0o600); err != nil {
			srv.Close()
			return err
		}
	}
	lb.srv = srv
	return nil
}

func readTrim(path string) (string, error) {
	b, err := os.ReadFile(path)
	return strings.TrimSpace(string(b)), err
}

// SetUpstream sets the PhotoPrism URL as seen from inside the VPN. tlsMode is
// "system" or "pin:<sha256 hex of the leaf certificate>".
func SetUpstream(rawURL string, tlsMode string) error {
	up, err := proxy.ParseUpstream(rawURL, tlsMode)
	if err != nil {
		return err
	}
	lb.mu.Lock()
	defer lb.mu.Unlock()
	lb.upstream = up
	if lb.srv != nil {
		lb.srv.SetUpstream(up)
	}
	return nil
}

// UIPort is the fixed loopback port for the WebView, or 0 before the first Acquire.
func UIPort() int {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	if lb.srv == nil {
		return 0
	}
	return lb.srv.UIPort()
}

// UIBootstrapSecret issues a one-time secret. Load
// http://127.0.0.1:<UIPort>/__scry/bootstrap?s=<secret> once to set the session cookie.
func UIBootstrapSecret() string {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	if lb.srv == nil {
		return ""
	}
	s, err := lb.srv.NewBootstrapSecret()
	if err != nil {
		return ""
	}
	return s
}

// WorkerEndpoint is host:port of the worker listener. Send the token in X-Scry-Token.
func WorkerEndpoint() string {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	if lb.srv == nil {
		return ""
	}
	return lb.srv.WorkerEndpoint()
}

func WorkerToken() string {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	if lb.srv == nil {
		return ""
	}
	return lb.srv.WorkerToken()
}

// SetDebugLogging turns on proxy request logging (paths and status codes only).
func SetDebugLogging(on bool) {
	proxy.Debug.Store(on)
	tunnel.Debug.Store(on)
	tstunnel.Debug.Store(on)
}
