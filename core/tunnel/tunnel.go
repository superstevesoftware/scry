// Package tunnel runs a userspace WireGuard device on top of gVisor netstack.
// It never touches the OS network configuration or Android's VpnService.
package tunnel

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"log"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"

	"scry/core/wgconfig"
)

// Debug turns on wireguard-go's own logging. Only set it in debug builds.
var Debug atomic.Bool

type Tunnel struct {
	dev *device.Device
	net *netstack.Net
}

// Start brings up the tunnel. The caller owns the returned Tunnel and must Close it.
func Start(c *wgconfig.Config) (*Tunnel, error) {
	var local, dns []netip.Addr
	for _, a := range c.Addresses {
		p, err := netip.ParsePrefix(a)
		if err != nil {
			return nil, err
		}
		local = append(local, p.Addr())
	}
	for _, d := range c.DNS {
		a, err := netip.ParseAddr(d)
		if err != nil {
			return nil, err
		}
		dns = append(dns, a)
	}

	// wireguard-go's IPC wants an IP endpoint, so resolve the hostname here.
	// This lookup uses the system resolver, outside the tunnel.
	t0 := time.Now()
	ep, err := net.ResolveUDPAddr("udp", c.Endpoint)
	log.Printf("scry-tunnel: resolved endpoint in %v", time.Since(t0))
	if err != nil {
		return nil, fmt.Errorf("cannot resolve endpoint: %w", err)
	}

	ipc, err := buildIPC(c, ep.AddrPort().String())
	if err != nil {
		return nil, err
	}

	tdev, tnet, err := netstack.CreateNetTUN(local, dns, c.MTU)
	if err != nil {
		return nil, err
	}
	// Silent unless debugging: wireguard-go's log names peers by abbreviated key and shows
	// endpoints, which release builds must never print. It does not print key material.
	level := device.LogLevelSilent
	if Debug.Load() {
		level = device.LogLevelVerbose
	}
	dev := device.NewDevice(tdev, conn.NewDefaultBind(), device.NewLogger(level, "scry-wg: "))
	if err := dev.IpcSet(ipc); err != nil {
		dev.Close()
		return nil, fmt.Errorf("configure device: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, fmt.Errorf("bring device up: %w", err)
	}
	log.Printf("scry-tunnel: device up, %v since start", time.Since(t0))
	return &Tunnel{dev: dev, net: tnet}, nil
}

func (t *Tunnel) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	return t.net.DialContext(ctx, network, addr)
}

func (t *Tunnel) Close() { t.dev.Close() }

// Rebind reopens the UDP sockets after the device's network changed (Wi-Fi to cellular and
// back) so the tunnel sends from the new address, then nudges the peer to re-handshake.
// wireguard-go does this itself on Linux via netlink, which Android does not allow.
func (t *Tunnel) Rebind() {
	_ = t.dev.BindUpdate()
	t.dev.SendKeepalivesToPeersWithCurrentKeypair()
}

// LastHandshake returns the time of the last completed handshake, or zero if none yet.
func (t *Tunnel) LastHandshake() time.Time {
	out, err := t.dev.IpcGet()
	if err != nil {
		return time.Time{}
	}
	var sec, nsec int64
	for _, line := range strings.Split(out, "\n") {
		k, v, _ := strings.Cut(line, "=")
		switch k {
		case "last_handshake_time_sec":
			sec, _ = strconv.ParseInt(v, 10, 64)
		case "last_handshake_time_nsec":
			nsec, _ = strconv.ParseInt(v, 10, 64)
		}
	}
	if sec == 0 && nsec == 0 {
		return time.Time{}
	}
	return time.Unix(sec, nsec)
}

// WaitHandshake blocks until a handshake completes or the context ends.
func (t *Tunnel) WaitHandshake(ctx context.Context) error {
	tick := time.NewTicker(200 * time.Millisecond)
	defer tick.Stop()
	for {
		if !t.LastHandshake().IsZero() {
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("no handshake: %w", ctx.Err())
		case <-tick.C:
		}
	}
}

func buildIPC(c *wgconfig.Config, endpoint string) (string, error) {
	priv, err := hexKey(c.PrivateKey)
	if err != nil {
		return "", err
	}
	pub, err := hexKey(c.PeerPublicKey)
	if err != nil {
		return "", err
	}
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\nreplace_peers=true\npublic_key=%s\n", priv, pub)
	if c.PresharedKey != "" {
		psk, err := hexKey(c.PresharedKey)
		if err != nil {
			return "", err
		}
		fmt.Fprintf(&b, "preshared_key=%s\n", psk)
	}
	fmt.Fprintf(&b, "endpoint=%s\n", endpoint)
	if c.Keepalive > 0 {
		fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", c.Keepalive)
	}
	for _, a := range c.AllowedIPs {
		fmt.Fprintf(&b, "allowed_ip=%s\n", a)
	}
	return b.String(), nil
}

func hexKey(b64 string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil || len(raw) != 32 {
		return "", fmt.Errorf("invalid key")
	}
	return hex.EncodeToString(raw), nil
}
