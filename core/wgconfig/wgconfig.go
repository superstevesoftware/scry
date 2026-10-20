// Package wgconfig parses and validates standard wg-quick .conf files.
package wgconfig

import (
	"encoding/base64"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

const DefaultMTU = 1280

type Config struct {
	PrivateKey string   `json:"privateKey"`
	Addresses  []string `json:"addresses"`
	DNS        []string `json:"dns,omitempty"`
	MTU        int      `json:"mtu"`

	PeerPublicKey string   `json:"peerPublicKey"`
	PresharedKey  string   `json:"presharedKey,omitempty"`
	Endpoint      string   `json:"endpoint"`
	AllowedIPs    []string `json:"allowedIPs"`
	Keepalive     int      `json:"keepalive,omitempty"`
}

// Parse reads wg-quick text. Unknown keys (PostUp, Table, ...) are ignored.
// Error messages never include key material.
func Parse(text string) (*Config, error) {
	c := &Config{MTU: DefaultMTU}
	section := ""
	peers := 0
	for n, line := range strings.Split(text, "\n") {
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = line[:i]
		}
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.TrimSpace(line[1 : len(line)-1]))
			if section == "peer" {
				peers++
				if peers > 1 {
					return nil, fmt.Errorf("multiple [Peer] sections are not supported")
				}
			}
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			return nil, fmt.Errorf("line %d: expected key = value", n+1)
		}
		k, v = strings.ToLower(strings.TrimSpace(k)), strings.TrimSpace(v)
		switch {
		case section == "interface" && k == "privatekey":
			c.PrivateKey = v
		case section == "interface" && k == "address":
			c.Addresses = append(c.Addresses, splitList(v)...)
		case section == "interface" && k == "dns":
			c.DNS = append(c.DNS, splitList(v)...)
		case section == "interface" && k == "mtu":
			m, err := strconv.Atoi(v)
			if err != nil {
				return nil, fmt.Errorf("line %d: invalid MTU", n+1)
			}
			c.MTU = m
		case section == "peer" && k == "publickey":
			c.PeerPublicKey = v
		case section == "peer" && k == "presharedkey":
			c.PresharedKey = v
		case section == "peer" && k == "endpoint":
			c.Endpoint = v
		case section == "peer" && k == "allowedips":
			c.AllowedIPs = append(c.AllowedIPs, splitList(v)...)
		case section == "peer" && k == "persistentkeepalive":
			if v != "off" {
				s, err := strconv.Atoi(v)
				if err != nil {
					return nil, fmt.Errorf("line %d: invalid PersistentKeepalive", n+1)
				}
				c.Keepalive = s
			}
		}
	}
	if err := c.Validate(); err != nil {
		return nil, err
	}
	return c, nil
}

func splitList(v string) []string {
	var out []string
	for _, p := range strings.Split(v, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func validKey(k string) bool {
	b, err := base64.StdEncoding.DecodeString(k)
	return err == nil && len(b) == 32
}

// Validate checks key format/length, endpoint host:port, CIDR syntax and ranges.
func (c *Config) Validate() error {
	if !validKey(c.PrivateKey) {
		return fmt.Errorf("private key missing or not a valid 32-byte base64 key")
	}
	if !validKey(c.PeerPublicKey) {
		return fmt.Errorf("peer public key missing or not a valid 32-byte base64 key")
	}
	if c.PresharedKey != "" && !validKey(c.PresharedKey) {
		return fmt.Errorf("preshared key is not a valid 32-byte base64 key")
	}
	if len(c.Addresses) == 0 {
		return fmt.Errorf("at least one interface address is required")
	}
	for _, a := range c.Addresses {
		if _, err := netip.ParsePrefix(a); err != nil {
			return fmt.Errorf("invalid address %q (expected CIDR)", a)
		}
	}
	for _, a := range c.AllowedIPs {
		if _, err := netip.ParsePrefix(a); err != nil {
			return fmt.Errorf("invalid allowed IP %q (expected CIDR)", a)
		}
	}
	for _, d := range c.DNS {
		if _, err := netip.ParseAddr(d); err != nil {
			return fmt.Errorf("invalid DNS address %q", d)
		}
	}
	host, port, err := net.SplitHostPort(c.Endpoint)
	if err != nil || host == "" {
		return fmt.Errorf("endpoint must be host:port")
	}
	if p, err := strconv.Atoi(port); err != nil || p < 1 || p > 65535 {
		return fmt.Errorf("endpoint port out of range")
	}
	if c.MTU < 576 || c.MTU > 1500 {
		return fmt.Errorf("MTU must be between 576 and 1500")
	}
	if c.Keepalive < 0 || c.Keepalive > 65535 {
		return fmt.Errorf("PersistentKeepalive out of range")
	}
	return nil
}
