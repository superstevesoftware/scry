package core

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"scry/core/proxy"
)

// Server identity lets the app skip the VPN when the server answers directly (for example
// on the home network) without trusting whatever happens to sit at that address.
//
// PhotoPrism publishes an instance-specific storageNamespace on its unauthenticated
// /api/v1/config. The app learns it once over the VPN (where WireGuard authenticates the
// server) and later accepts a direct address only if it reports the same value. Only
// public endpoints are touched and no credentials are sent. The value is hashed so the
// raw namespace never leaves this package.

type dialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

func fetchIdentity(ctx context.Context, dial dialFunc, base *url.URL) (string, error) {
	tr := &http.Transport{DialContext: dial, DisableKeepAlives: true, DisableCompression: true}
	defer tr.CloseIdleConnections()
	client := &http.Client{
		Transport:     tr,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base.Scheme+"://"+base.Host+"/api/v1/config", nil)
	if err != nil {
		return "", err
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var doc struct {
		StorageNamespace string `json:"storageNamespace"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 8<<20)).Decode(&doc); err != nil || doc.StorageNamespace == "" {
		return "", fmt.Errorf("not a PhotoPrism server")
	}
	sum := sha256.Sum256([]byte(doc.StorageNamespace))
	return hex.EncodeToString(sum[:]), nil
}

// ProbeIdentity asks rawURL directly over the device's network, not through the tunnel,
// and returns the server's identity hash. timeoutMillis bounds the whole attempt,
// including DNS and connecting, so a silent address cannot hold up startup.
func ProbeIdentity(rawURL string, timeoutMillis int) (string, error) {
	up, err := proxy.ParseUpstream(rawURL, "system")
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMillis)*time.Millisecond)
	defer cancel()
	var d net.Dialer
	return fetchIdentity(ctx, d.DialContext, up.URL)
}

// fetchIdentityRetrying keeps trying with a fresh connection each time until it succeeds or
// the total time is used up. A tunnel that has just started can need several seconds before
// the server is reachable through it, and a single connection attempt backs off its retries
// too slowly to notice the moment the path comes up. A refusal is a real answer from the
// server (nothing is listening), so it ends the wait at once.
func fetchIdentityRetrying(dial dialFunc, base *url.URL, total, perAttempt time.Duration) (string, error) {
	deadline := time.Now().Add(total)
	var last error
	for {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			break
		}
		per := perAttempt
		if remaining < per {
			per = remaining
		}
		ctx, cancel := context.WithTimeout(context.Background(), per)
		id, err := fetchIdentity(ctx, dial, base)
		cancel()
		if err == nil {
			return id, nil
		}
		last = err
		if strings.Contains(err.Error(), "refused") {
			return "", err
		}
		time.Sleep(250 * time.Millisecond)
	}
	if last == nil {
		last = fmt.Errorf("timed out")
	}
	return "", last
}

// TunnelIdentity asks the configured upstream through the running tunnel, retrying while
// the tunnel finishes coming up. timeoutMillis bounds the whole wait.
func TunnelIdentity(timeoutMillis int) (string, error) {
	lb.mu.Lock()
	up := lb.upstream
	lb.mu.Unlock()
	if up == nil {
		return "", fmt.Errorf("no upstream configured")
	}
	return fetchIdentityRetrying(tunnelDial, up.URL, time.Duration(timeoutMillis)*time.Millisecond, 3*time.Second)
}
