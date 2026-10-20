package core

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"testing"
	"time"

	"scry/core/wgconfig"
)

// origStart is the real start function, before tests swap it for a fake.
var origStart = startFn

const key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

type fake struct {
	closed bool
	target string // address DialContext connects to, if set
}

var stateDir string

func (f *fake) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, network, f.target)
}

func (f *fake) Close()                   { f.closed = true }
func (f *fake) LastHandshake() time.Time { return time.Time{} }

func cfg(endpoint string) string {
	b, _ := json.Marshal(&Config{Kind: "wireguard", WireGuard: &wgconfig.Config{
		PrivateKey: key, PeerPublicKey: key, Addresses: []string{"10.0.0.2/32"},
		Endpoint: endpoint, AllowedIPs: []string{"10.0.0.0/24"}, MTU: 1280,
	}})
	return string(b)
}

func TestMain(m *testing.M) {
	stateDir, _ = os.MkdirTemp("", "scry-core-test")
	code := m.Run()
	os.RemoveAll(stateDir)
	os.Exit(code)
}

func setup(t *testing.T) (starts *[]*fake, mu *sync.Mutex) {
	t.Helper()
	m.mu.Lock()
	m.stopLocked()
	m.leases = map[string]struct{}{}
	m.cfgJSON, m.cfg = "", nil
	m.grace = 50 * time.Millisecond
	m.mu.Unlock()
	var list []*fake
	var lock sync.Mutex
	startFn = func(*Config) (runner, error) {
		f := &fake{}
		lock.Lock()
		list = append(list, f)
		lock.Unlock()
		return f, nil
	}
	t.Cleanup(func() {
		m.mu.Lock()
		m.stopLocked()
		m.mu.Unlock()
	})
	return &list, &lock
}

func TestLeaseLifecycleAndGrace(t *testing.T) {
	starts, lock := setup(t)
	a, err := Acquire(cfg("1.2.3.4:51820"), stateDir)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := Acquire(cfg("1.2.3.4:51820"), stateDir) // second lease must not start a second tunnel
	if err := Await(2000); err != nil {
		t.Fatal(err)
	}
	lock.Lock()
	if len(*starts) != 1 {
		t.Fatalf("want 1 start, got %d", len(*starts))
	}
	lock.Unlock()

	Release(a)
	time.Sleep(150 * time.Millisecond)
	if (*starts)[0].closed {
		t.Fatal("tunnel stopped while a lease was still held")
	}
	Release(b)
	time.Sleep(200 * time.Millisecond)
	if !(*starts)[0].closed {
		t.Fatal("tunnel should stop after grace period")
	}
}

func TestReacquireDuringGraceKeepsTunnel(t *testing.T) {
	starts, _ := setup(t)
	a, _ := Acquire(cfg("1.2.3.4:51820"), stateDir)
	Await(2000)
	Release(a)
	b, _ := Acquire(cfg("1.2.3.4:51820"), stateDir)
	time.Sleep(200 * time.Millisecond)
	if len(*starts) != 1 || (*starts)[0].closed {
		t.Fatal("tunnel should have survived re-acquire within grace")
	}
	Release(b)
}

func TestConfigChangeRestartsAndKeepsLeases(t *testing.T) {
	starts, _ := setup(t)
	a, _ := Acquire(cfg("1.2.3.4:51820"), stateDir)
	Await(2000)
	b, _ := Acquire(cfg("5.6.7.8:51820"), stateDir)
	Await(2000)
	if len(*starts) != 2 || !(*starts)[0].closed || (*starts)[1].closed {
		t.Fatalf("expected restart, got %d starts", len(*starts))
	}
	var st map[string]any
	json.Unmarshal([]byte(Status()), &st)
	if st["leases"].(float64) != 2 || st["state"] != StateUp {
		t.Fatalf("bad status: %v", st)
	}
	Release(a)
	Release(b)
}

func TestInvalidConfigRejected(t *testing.T) {
	setup(t)
	if _, err := Acquire(`{"kind":"wireguard","wireguard":{}}`, stateDir); err == nil {
		t.Fatal("expected validation error")
	}
	if _, err := Acquire("not json", stateDir); err == nil {
		t.Fatal("expected JSON error")
	}
}

func TestEndToEndThroughLoopbackAndTunnel(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "host=%s", r.Host)
	}))
	defer up.Close()
	setup(t)
	startFn = func(*Config) (runner, error) { return &fake{target: up.Listener.Addr().String()}, nil }
	if err := SetUpstream("http://photos.internal:2342", "system"); err != nil {
		t.Fatal(err)
	}
	id, err := Acquire(cfg("1.2.3.4:51820"), stateDir)
	if err != nil {
		t.Fatal(err)
	}
	defer Release(id)
	if err := Await(2000); err != nil {
		t.Fatal(err)
	}
	base := fmt.Sprintf("http://127.0.0.1:%d", UIPort())

	// Bootstrap sets the cookie, then the proxied page comes back through the "tunnel".
	jar := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := jar.Get(base + "/__scry/bootstrap?s=" + UIBootstrapSecret())
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("bootstrap: %v %v", err, resp)
	}
	req, _ := http.NewRequest("GET", base+"/", nil)
	req.AddCookie(resp.Cookies()[0])
	r2, err := jar.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(r2.Body)
	if string(b) != "host=photos.internal:2342" {
		t.Fatalf("got %q", b)
	}

	// Worker listener path.
	wreq, _ := http.NewRequest("GET", "http://"+WorkerEndpoint()+"/", nil)
	wreq.Header.Set("X-Scry-Token", WorkerToken())
	wr, err := jar.Do(wreq)
	if err != nil || wr.StatusCode != 200 {
		t.Fatalf("worker: %v %v", err, wr)
	}
}

func TestDirectKindDialsWithoutTunnel(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "hello") }))
	defer up.Close()
	r, err := directRunner{}.DialContext(context.Background(), "tcp", up.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	r.Close()
	got, err := origStart(&Config{Kind: "direct"})
	if err != nil || got == nil {
		t.Fatal("direct kind should start instantly")
	}
}

func TestConfigChangeWhileConnectingReplacesIt(t *testing.T) {
	setup(t)
	release := make(chan struct{})
	var mu sync.Mutex
	var built []*fake
	startFn = func(c *Config) (runner, error) {
		if c.Kind == "wireguard" {
			<-release // the first (slow) tunnel is still connecting
		}
		f := &fake{}
		mu.Lock()
		built = append(built, f)
		mu.Unlock()
		return f, nil
	}
	a, _ := Acquire(cfg("1.2.3.4:51820"), stateDir)  // starts connecting, blocked
	b, err := Acquire(`{"kind":"direct"}`, stateDir) // must replace it, not be ignored
	if err != nil {
		t.Fatal(err)
	}
	if err := Await(2000); err != nil {
		t.Fatal(err)
	}
	close(release)
	time.Sleep(100 * time.Millisecond)
	var st map[string]any
	json.Unmarshal([]byte(Status()), &st)
	if st["kind"] != "direct" || st["state"] != StateUp {
		t.Fatalf("direct config was ignored: %v", st)
	}
	mu.Lock()
	defer mu.Unlock()
	closed := 0
	for _, f := range built {
		if f.closed {
			closed++
		}
	}
	if len(built) != 2 || closed != 1 {
		t.Fatalf("the stale tunnel should be closed and the direct one kept: built=%d closed=%d", len(built), closed)
	}
	Release(a)
	Release(b)
}
