package tstunnel

import (
	"os"
	"path/filepath"
	"testing"
)

func TestValidate(t *testing.T) {
	base := t.TempDir()

	// First sign-in needs a key.
	if err := (&Config{Hostname: "scry-phone"}).Validate(base); err == nil {
		t.Error("expected an error without an auth key on first sign-in")
	}
	// Default node name when none is given.
	c := &Config{AuthKey: "tskey-auth-abc"}
	if err := c.Validate(base); err != nil || c.Hostname != "scry" {
		t.Errorf("got %v, hostname %q", err, c.Hostname)
	}
	// Names are normalised and checked.
	c = &Config{AuthKey: "tskey-auth-abc", Hostname: "  Scry-Phone "}
	if err := c.Validate(base); err != nil || c.Hostname != "scry-phone" {
		t.Errorf("got %v, hostname %q", err, c.Hostname)
	}
	for _, bad := range []string{"has space", "under_score", "-lead", "trail-", "dots.in.name"} {
		if err := (&Config{AuthKey: "tskey-auth-abc", Hostname: bad}).Validate(base); err == nil {
			t.Errorf("hostname %q should be rejected", bad)
		}
	}
	// Obvious wrong input is caught before any network use.
	if err := (&Config{AuthKey: "not-a-key"}).Validate(base); err == nil {
		t.Error("a key without the tskey- prefix should be rejected")
	}
	if err := (&Config{AuthKey: "tskey-auth-abc", ControlURL: "ftp://x"}).Validate(base); err == nil {
		t.Error("control URL must be http(s)")
	}
}

func TestSavedStateMakesKeyOptional(t *testing.T) {
	base := t.TempDir()
	if HasState(base) {
		t.Fatal("no state yet")
	}
	if err := os.MkdirAll(filepath.Join(base, "tailscale"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(base, "tailscale", "tailscaled.state"), []byte("{}"), 0o600); err != nil {
		t.Fatal(err)
	}
	if !HasState(base) {
		t.Fatal("state should be detected")
	}
	if err := (&Config{Hostname: "scry"}).Validate(base); err != nil {
		t.Errorf("a signed-in device needs no key: %v", err)
	}
}
