// Package proxy provides the two loopback listeners in front of PhotoPrism:
// a UI reverse proxy for the WebView and a token-protected one for workers.
// Both reach the server only through the supplied Dialer (the tunnel).
package proxy

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	SessionCookie = "scry_session"
	BootstrapPath = "/__scry/bootstrap"
	WorkerHeader  = "X-Scry-Token"
)

// Debug enables request logging (method, path, status; never queries or headers).
var Debug atomic.Bool

func dbg(format string, a ...any) {
	if Debug.Load() {
		log.Printf("scry-proxy: "+format, a...)
	}
}

// Dialer opens connections to the upstream, normally via the tunnel.
type Dialer func(ctx context.Context, network, addr string) (net.Conn, error)

// Upstream describes where PhotoPrism lives as seen from inside the VPN.
type Upstream struct {
	URL *url.URL
	// PinSHA256 is the hex SHA-256 of the server's leaf certificate for
	// trust-on-first-use self-signed setups. Empty means normal verification.
	PinSHA256 string
}

// ParseUpstream accepts rawURL plus a tlsMode of "system" or "pin:<sha256 hex>".
func ParseUpstream(rawURL, tlsMode string) (*Upstream, error) {
	u, err := url.Parse(rawURL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, fmt.Errorf("upstream must be an http(s) URL")
	}
	u.Path, u.RawQuery, u.Fragment = "", "", ""
	up := &Upstream{URL: u}
	switch {
	case tlsMode == "" || tlsMode == "system":
	case strings.HasPrefix(tlsMode, "pin:"):
		up.PinSHA256 = strings.ToLower(strings.TrimPrefix(tlsMode, "pin:"))
		if b, err := hex.DecodeString(up.PinSHA256); err != nil || len(b) != 32 {
			return nil, fmt.Errorf("pin must be a hex SHA-256 fingerprint")
		}
	default:
		return nil, fmt.Errorf("unknown tls mode %q", tlsMode)
	}
	return up, nil
}

func (u *Upstream) transport(dial Dialer) *http.Transport {
	t := &http.Transport{
		DialContext:           dial,
		ForceAttemptHTTP2:     false,
		MaxIdleConnsPerHost:   8,
		IdleConnTimeout:       60 * time.Second,
		TLSHandshakeTimeout:   20 * time.Second,
		ResponseHeaderTimeout: 0, // large uploads and slow servers must not stall
		DisableCompression:    true,
	}
	if u.PinSHA256 != "" {
		pin := u.PinSHA256
		t.TLSClientConfig = &tls.Config{
			InsecureSkipVerify: true, // replaced by the pin check below
			VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
				if len(raw) == 0 {
					return fmt.Errorf("no certificate presented")
				}
				sum := sha256.Sum256(raw[0])
				if hex.EncodeToString(sum[:]) != pin {
					return fmt.Errorf("server certificate does not match pinned fingerprint")
				}
				return nil
			},
		}
	}
	return t
}

// Fingerprint dials rawURL's host and returns the leaf certificate SHA-256, for
// the trust-on-first-use prompt. No verification is done; the caller must show it to the user.
func Fingerprint(ctx context.Context, dial Dialer, hostPort string) (string, error) {
	c, err := dial(ctx, "tcp", hostPort)
	if err != nil {
		return "", err
	}
	host, _, _ := net.SplitHostPort(hostPort)
	tc := tls.Client(c, &tls.Config{InsecureSkipVerify: true, ServerName: host})
	defer tc.Close()
	if err := tc.HandshakeContext(ctx); err != nil {
		return "", err
	}
	certs := tc.ConnectionState().PeerCertificates
	if len(certs) == 0 {
		return "", fmt.Errorf("no certificate presented")
	}
	sum := sha256.Sum256(certs[0].Raw)
	return hex.EncodeToString(sum[:]), nil
}

// Server owns both listeners.
type Server struct {
	dial Dialer

	mu       sync.RWMutex
	upstream *Upstream
	tr       *http.Transport

	sessionToken string

	bmu       sync.Mutex
	bootstrap string // single outstanding one-time secret

	workerToken string

	uiLn, workerLn net.Listener
	uiSrv, wSrv    *http.Server
}

// New creates a server. sessionToken is the persisted cookie value that keeps
// the WebView logged in across restarts; it must be high-entropy.
func New(dial Dialer, sessionToken string) (*Server, error) {
	if len(sessionToken) < 32 {
		return nil, fmt.Errorf("session token too short")
	}
	wt, err := RandomToken(32)
	if err != nil {
		return nil, err
	}
	return &Server{dial: dial, sessionToken: sessionToken, workerToken: wt}, nil
}

func (s *Server) SetUpstream(u *Upstream) {
	s.mu.Lock()
	old := s.tr
	s.upstream, s.tr = u, u.transport(s.dial)
	s.mu.Unlock()
	if old != nil {
		old.CloseIdleConnections()
	}
}

func (s *Server) current() (*Upstream, *http.Transport) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.upstream, s.tr
}

// Start binds both listeners. uiPort is the persisted port (0 picks a new high
// port); a failed bind on a non-zero port is returned so the caller can tell the user.
func (s *Server) Start(uiPort int) error {
	var err error
	if uiPort != 0 {
		s.uiLn, err = net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", uiPort))
	} else {
		s.uiLn, err = listenHighPort()
	}
	if err != nil {
		return fmt.Errorf("cannot bind UI port: %w", err)
	}
	s.workerLn, err = net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		s.uiLn.Close()
		return err
	}
	s.uiSrv = &http.Server{Handler: s.uiHandler(), ReadHeaderTimeout: 30 * time.Second}
	s.wSrv = &http.Server{Handler: s.workerHandler(), ReadHeaderTimeout: 30 * time.Second}
	go s.uiSrv.Serve(s.uiLn)
	go s.wSrv.Serve(s.workerLn)
	return nil
}

func (s *Server) Close() {
	for _, srv := range []*http.Server{s.uiSrv, s.wSrv} {
		if srv != nil {
			srv.Close()
		}
	}
}

func (s *Server) UIPort() int            { return s.uiLn.Addr().(*net.TCPAddr).Port }
func (s *Server) WorkerEndpoint() string { return s.workerLn.Addr().String() }
func (s *Server) WorkerToken() string    { return s.workerToken }

// NewBootstrapSecret issues a one-time secret, replacing any unused earlier one.
func (s *Server) NewBootstrapSecret() (string, error) {
	t, err := RandomToken(24)
	if err != nil {
		return "", err
	}
	s.bmu.Lock()
	s.bootstrap = t
	s.bmu.Unlock()
	return t, nil
}

func (s *Server) consumeBootstrap(got string) bool {
	s.bmu.Lock()
	defer s.bmu.Unlock()
	if s.bootstrap == "" || subtle.ConstantTimeCompare([]byte(got), []byte(s.bootstrap)) != 1 {
		return false
	}
	s.bootstrap = ""
	return true
}

func hostAllowed(host string, port int) bool {
	return host == fmt.Sprintf("127.0.0.1:%d", port) || host == fmt.Sprintf("localhost:%d", port)
}

func (s *Server) uiHandler() http.Handler {
	port := s.UIPort()
	loopOrigin := fmt.Sprintf("http://127.0.0.1:%d", port)
	rp := s.reverseProxy(loopOrigin, true)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, hasCookie := r.Cookie(SessionCookie)
		dbg("ui %s %s host=%s cookie=%v", r.Method, r.URL.Path, r.Host, hasCookie == nil)
		if !hostAllowed(r.Host, port) { // blocks DNS rebinding
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if r.URL.Path == BootstrapPath {
			ok := s.consumeBootstrap(r.URL.Query().Get("s"))
			dbg("bootstrap accepted=%v headers=%s", ok, headerSummary(r.Header))
			if Debug.Load() {
				w = &traceWriter{ResponseWriter: w}
			}
			if !ok {
				http.Error(w, "forbidden", http.StatusForbidden)
				return
			}
			http.SetCookie(w, &http.Cookie{
				Name: SessionCookie, Value: s.sessionToken, Path: "/",
				HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: 10 * 365 * 24 * 3600,
			})
			// An HTML hop instead of a 302: a WebView's first navigation after process
			// start was observed not to follow the redirect.
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Header().Set("Cache-Control", "no-store")
			io.WriteString(w, "<!doctype html><meta charset=utf-8><meta http-equiv=refresh content=\"0;url=/\"><script>location.replace('/')</script>")
			return
		}
		c, err := r.Cookie(SessionCookie)
		if err != nil || subtle.ConstantTimeCompare([]byte(c.Value), []byte(s.sessionToken)) != 1 {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		rp.ServeHTTP(w, r)
	})
}

func (s *Server) workerHandler() http.Handler {
	port := s.workerLn.Addr().(*net.TCPAddr).Port
	rp := s.reverseProxy(fmt.Sprintf("http://127.0.0.1:%d", port), false)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !hostAllowed(r.Host, port) ||
			subtle.ConstantTimeCompare([]byte(r.Header.Get(WorkerHeader)), []byte(s.workerToken)) != 1 {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		r.Header.Del(WorkerHeader)
		rp.ServeHTTP(w, r)
	})
}

func (s *Server) reverseProxy(loopOrigin string, ui bool) *httputil.ReverseProxy {
	return &httputil.ReverseProxy{
		Transport:     roundTripper(s.current),
		FlushInterval: -1, // stream immediately
		Rewrite: func(pr *httputil.ProxyRequest) {
			up, _ := s.current()
			if up == nil {
				return
			}
			pr.SetURL(up.URL) // also makes the outbound Host the upstream's
			upOrigin := up.URL.Scheme + "://" + up.URL.Host
			h := pr.Out.Header
			if o := h.Get("Origin"); o == loopOrigin {
				h.Set("Origin", upOrigin)
			}
			if ref := h.Get("Referer"); strings.HasPrefix(ref, loopOrigin) {
				h.Set("Referer", upOrigin+strings.TrimPrefix(ref, loopOrigin))
			}
			if ui {
				stripCookie(h, SessionCookie)
			}
		},
		ModifyResponse: func(resp *http.Response) error {
			dbg("upstream %d %s", resp.StatusCode, resp.Request.URL.Path)
			up, _ := s.current()
			if up == nil {
				return nil
			}
			upOrigin := up.URL.Scheme + "://" + up.URL.Host
			if loc := resp.Header.Get("Location"); strings.HasPrefix(loc, upOrigin) {
				resp.Header.Set("Location", loopOrigin+strings.TrimPrefix(loc, upOrigin))
			}
			if ui {
				rewriteSetCookies(resp.Header)
			}
			return nil
		},
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			dbg("upstream error %s: %v", r.URL.Path, err)
			http.Error(w, "upstream unreachable", http.StatusBadGateway)
		},
	}
}

// roundTripper picks up the current transport on every request so SetUpstream takes effect.
type roundTripper func() (*Upstream, *http.Transport)

func (f roundTripper) RoundTrip(r *http.Request) (*http.Response, error) {
	_, t := f()
	if t == nil {
		return nil, fmt.Errorf("no upstream configured")
	}
	return t.RoundTrip(r)
}

func stripCookie(h http.Header, name string) {
	req := http.Request{Header: h}
	cs := req.Cookies()
	h.Del("Cookie")
	for _, c := range cs {
		if c.Name != name {
			req.AddCookie(c)
		}
	}
}

// rewriteSetCookies makes upstream cookies valid on the http loopback origin.
func rewriteSetCookies(h http.Header) {
	vals := h.Values("Set-Cookie")
	if len(vals) == 0 {
		return
	}
	h.Del("Set-Cookie")
	for _, v := range vals {
		var out []string
		for _, part := range strings.Split(v, ";") {
			p := strings.TrimSpace(part)
			l := strings.ToLower(p)
			switch {
			case strings.HasPrefix(l, "domain="), l == "secure":
				continue
			case l == "samesite=none":
				p = "SameSite=Lax"
			}
			out = append(out, p)
		}
		h.Add("Set-Cookie", strings.Join(out, "; "))
	}
}

// RandomToken returns n random bytes, hex encoded.
func RandomToken(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func listenHighPort() (net.Listener, error) {
	for i := 0; i < 50; i++ {
		n, err := rand.Int(rand.Reader, big.NewInt(20000))
		if err != nil {
			return nil, err
		}
		if ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", 40000+n.Int64())); err == nil {
			return ln, nil
		}
	}
	return nil, fmt.Errorf("no free port found")
}

// headerSummary lists header names and short values, redacting cookies.
func headerSummary(h http.Header) string {
	var b strings.Builder
	for k, v := range h {
		if strings.EqualFold(k, "Cookie") {
			b.WriteString(k + "=<redacted> ")
			continue
		}
		b.WriteString(k + "=" + strings.Join(v, ",") + " ")
	}
	return b.String()
}

type traceWriter struct{ http.ResponseWriter }

func (t *traceWriter) WriteHeader(code int) {
	dbg("bootstrap response %d location=%q setcookie=%d", code, t.Header().Get("Location"), len(t.Header().Values("Set-Cookie")))
	t.ResponseWriter.WriteHeader(code)
}
