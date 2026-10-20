package tunnel

import (
	"strings"
	"testing"

	"scry/core/wgconfig"
)

func TestBuildIPC(t *testing.T) {
	const k = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
	c := &wgconfig.Config{PrivateKey: k, PeerPublicKey: k, AllowedIPs: []string{"10.0.0.0/24"}, Keepalive: 25}
	got, err := buildIPC(c, "1.2.3.4:51820")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"private_key=" + strings.Repeat("00", 32),
		"endpoint=1.2.3.4:51820",
		"persistent_keepalive_interval=25",
		"allowed_ip=10.0.0.0/24",
	} {
		if !strings.Contains(got, want) {
			t.Errorf("missing %q in:\n%s", want, got)
		}
	}
}
