package proxy

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

const session = "0123456789abcdef0123456789abcdef0123456789abcdef"

func directDial(ctx context.Context, network, addr string) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, network, addr)
}

func newServer(t *testing.T, upstream http.Handler) (*Server, *httptest.Server) {
	t.Helper()
	up := httptest.NewServer(upstream)
	t.Cleanup(up.Close)
	s, err := New(directDial, session)
	if err != nil {
		t.Fatal(err)
	}
	u, err := ParseUpstream(up.URL, "system")
	if err != nil {
		t.Fatal(err)
	}
	s.SetUpstream(u)
	if err := s.Start(0); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Close)
	return s, up
}

func ui(s *Server) string { return fmt.Sprintf("http://127.0.0.1:%d", s.UIPort()) }

// noRedirect client that carries the session cookie by hand.
func get(t *testing.T, rawURL string, hdr map[string]string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest("GET", rawURL, nil)
	for k, v := range hdr {
		if strings.EqualFold(k, "Host") {
			req.Host = v
		} else {
			req.Header.Set(k, v)
		}
	}
	c := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func authed() map[string]string { return map[string]string{"Cookie": SessionCookie + "=" + session} }

func TestRejectsWithoutCookieAndBadHost(t *testing.T) {
	s, _ := newServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "secret") }))
	if r := get(t, ui(s)+"/", nil); r.StatusCode != 403 {
		t.Fatalf("no cookie: got %d", r.StatusCode)
	}
	h := authed()
	h["Host"] = "evil.example:" + fmt.Sprint(s.UIPort())
	if r := get(t, ui(s)+"/", h); r.StatusCode != 403 {
		t.Fatalf("bad host: got %d", r.StatusCode)
	}
}

func TestBootstrapIsOneTimeAndSetsCookie(t *testing.T) {
	s, _ := newServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	sec, _ := s.NewBootstrapSecret()
	r := get(t, ui(s)+BootstrapPath+"?s="+sec, nil)
	if r.StatusCode != 200 {
		t.Fatalf("got %d", r.StatusCode)
	}
	var found *http.Cookie
	for _, c := range r.Cookies() {
		if c.Name == SessionCookie {
			found = c
		}
	}
	if found == nil || !found.HttpOnly || found.SameSite != http.SameSiteStrictMode {
		t.Fatalf("bad cookie: %+v", found)
	}
	if r := get(t, ui(s)+BootstrapPath+"?s="+sec, nil); r.StatusCode != 403 {
		t.Fatalf("secret reused: got %d", r.StatusCode)
	}
}

func TestHeaderAndCookieRewriting(t *testing.T) {
	var gotHost, gotOrigin, gotReferer, gotCookie string
	var upURL string
	s, up := newServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotHost, gotOrigin, gotReferer, gotCookie = r.Host, r.Header.Get("Origin"), r.Header.Get("Referer"), r.Header.Get("Cookie")
		w.Header().Add("Set-Cookie", "a=b; Domain=photos.internal; Secure; SameSite=None; Path=/")
		http.Redirect(w, r, upURL+"/library", http.StatusFound)
	}))
	upURL = up.URL
	h := authed()
	h["Cookie"] += "; other=1"
	h["Origin"] = ui(s)
	h["Referer"] = ui(s) + "/x"
	resp := get(t, ui(s)+"/", h)
	u, _ := url.Parse(up.URL)
	if gotHost != u.Host {
		t.Errorf("upstream Host = %q, want %q", gotHost, u.Host)
	}
	if gotOrigin != up.URL || gotReferer != up.URL+"/x" {
		t.Errorf("origin/referer not rewritten: %q %q", gotOrigin, gotReferer)
	}
	if strings.Contains(gotCookie, SessionCookie) || !strings.Contains(gotCookie, "other=1") {
		t.Errorf("cookie forwarded wrongly: %q", gotCookie)
	}
	if loc := resp.Header.Get("Location"); loc != ui(s)+"/library" {
		t.Errorf("Location = %q", loc)
	}
	sc := resp.Header.Get("Set-Cookie")
	if strings.Contains(strings.ToLower(sc), "domain") || strings.Contains(strings.ToLower(sc), "secure") || !strings.Contains(sc, "SameSite=Lax") {
		t.Errorf("Set-Cookie not rewritten: %q", sc)
	}
}

func TestRangePassthrough(t *testing.T) {
	data := strings.Repeat("0123456789", 1000)
	s, _ := newServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.ServeContent(w, r, "v.mp4", time.Time{}, strings.NewReader(data))
	}))
	h := authed()
	h["Range"] = "bytes=100-199"
	resp := get(t, ui(s)+"/v", h)
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 206 || len(b) != 100 || resp.Header.Get("Content-Range") != "bytes 100-199/10000" {
		t.Fatalf("status=%d len=%d cr=%q", resp.StatusCode, len(b), resp.Header.Get("Content-Range"))
	}
}

func TestWorkerRequiresToken(t *testing.T) {
	s, _ := newServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "auth="+r.Header.Get("Authorization")+" tok="+r.Header.Get(WorkerHeader))
	}))
	base := "http://" + s.WorkerEndpoint()
	if r := get(t, base+"/", nil); r.StatusCode != 403 {
		t.Fatalf("no token: %d", r.StatusCode)
	}
	r := get(t, base+"/", map[string]string{WorkerHeader: s.WorkerToken(), "Authorization": "Bearer abc"})
	b, _ := io.ReadAll(r.Body)
	if r.StatusCode != 200 || string(b) != "auth=Bearer abc tok=" {
		t.Fatalf("got %d %q", r.StatusCode, b)
	}
}

func TestWebSocketUpgrade(t *testing.T) {
	s, _ := newServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
			http.Error(w, "no upgrade", 400)
			return
		}
		c, rw, _ := w.(http.Hijacker).Hijack()
		defer c.Close()
		rw.WriteString("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n")
		rw.Flush()
		buf := make([]byte, 4)
		io.ReadFull(rw, buf)
		c.Write(buf)
	}))
	c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", s.UIPort()))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	fmt.Fprintf(c, "GET /ws HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nCookie: %s=%s\r\n\r\n", s.UIPort(), SessionCookie, session)
	br := bufio.NewReader(c)
	line, _ := br.ReadString('\n')
	if !strings.Contains(line, "101") {
		t.Fatalf("status line %q", line)
	}
	for {
		l, _ := br.ReadString('\n')
		if l == "\r\n" || l == "" {
			break
		}
	}
	c.Write([]byte("ping"))
	buf := make([]byte, 4)
	if _, err := io.ReadFull(br, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("echo failed: %v %q", err, buf)
	}
}

func TestParseUpstream(t *testing.T) {
	if _, err := ParseUpstream("ftp://x", ""); err == nil {
		t.Error("ftp accepted")
	}
	if _, err := ParseUpstream("https://x", "pin:zz"); err == nil {
		t.Error("bad pin accepted")
	}
	if _, err := ParseUpstream("https://photos.internal:2342/foo", "system"); err != nil {
		t.Error(err)
	}
}
