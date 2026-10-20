package wgconfig

import "testing"

const k = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

func TestParse(t *testing.T) {
	c, err := Parse("[Interface]\nPrivateKey = " + k + "\nAddress = 10.0.0.2/32, fd00::2/128\nDNS = 10.0.0.1\n\n[Peer]\nPublicKey = " + k + "\nEndpoint = vpn.example.com:51820\nAllowedIPs = 10.0.0.0/24\nPersistentKeepalive = 25\n")
	if err != nil {
		t.Fatal(err)
	}
	if c.MTU != DefaultMTU || len(c.Addresses) != 2 || c.Keepalive != 25 {
		t.Fatalf("unexpected: %+v", c)
	}
}

func TestRejectsBadEndpoint(t *testing.T) {
	_, err := Parse("[Interface]\nPrivateKey = " + k + "\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = " + k + "\nEndpoint = nohost\nAllowedIPs = 0.0.0.0/0\n")
	if err == nil {
		t.Fatal("expected error")
	}
}
