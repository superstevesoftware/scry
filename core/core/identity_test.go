package core

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync/atomic"
	"testing"
	"time"
)

func TestProbeIdentity(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/config" {
			http.NotFound(w, r)
			return
		}
		fmt.Fprint(w, `{"name":"PhotoPrism","storageNamespace":"abc123"}`)
	}))
	defer srv.Close()

	a, err := ProbeIdentity(srv.URL, 1000)
	if err != nil || len(a) != 64 {
		t.Fatalf("got %q, %v", a, err)
	}
	if b, _ := ProbeIdentity(srv.URL, 1000); a != b {
		t.Fatal("identity must be stable")
	}

	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"storageNamespace":"different"}`)
	}))
	defer other.Close()
	if c, _ := ProbeIdentity(other.URL, 1000); c == a {
		t.Fatal("different servers must have different identities")
	}

	notPP := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, `<html>router</html>`) }))
	defer notPP.Close()
	if _, err := ProbeIdentity(notPP.URL, 1000); err == nil {
		t.Fatal("a non-PhotoPrism server must not produce an identity")
	}
}

func TestProbeIdentityTimesOutQuickly(t *testing.T) {
	start := time.Now()
	// 192.0.2.0/24 is reserved for documentation: nothing answers, like a silent LAN address.
	_, err := ProbeIdentity("http://192.0.2.1:2342", 300)
	if err == nil {
		t.Fatal("expected failure")
	}
	if d := time.Since(start); d > 800*time.Millisecond {
		t.Fatalf("probe took %v, want about 300ms", d)
	}
}

func TestFetchIdentityRetriesUntilTheTunnelIsUp(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"storageNamespace":"abc123"}`)
	}))
	defer srv.Close()
	u, _ := url.Parse(srv.URL)

	var tries atomic.Int32
	var d net.Dialer
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		if tries.Add(1) <= 2 { // the first attempts hang, as through a tunnel that is still coming up
			<-ctx.Done()
			return nil, ctx.Err()
		}
		return d.DialContext(ctx, network, addr)
	}
	id, err := fetchIdentityRetrying(dial, u, 5*time.Second, 200*time.Millisecond)
	if err != nil || len(id) != 64 {
		t.Fatalf("got %q, %v after %d tries", id, err, tries.Load())
	}
	if tries.Load() != 3 {
		t.Fatalf("expected 3 tries, got %d", tries.Load())
	}
}

func TestFetchIdentityStopsOnRefusal(t *testing.T) {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	addr := ln.Addr().String()
	ln.Close() // nothing listens here any more
	u, _ := url.Parse("http://" + addr)
	var d net.Dialer
	start := time.Now()
	_, err := fetchIdentityRetrying(d.DialContext, u, 10*time.Second, time.Second)
	if err == nil {
		t.Fatal("expected an error")
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("a refused connection should end the wait quickly, took %v", time.Since(start))
	}
}
