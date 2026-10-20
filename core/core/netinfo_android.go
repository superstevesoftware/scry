//go:build android

package core

import (
	"encoding/json"
	"net"
	"net/netip"

	"tailscale.com/net/netmon"
)

type ifaceInfo struct {
	Name      string   `json:"name"`
	Index     int      `json:"index"`
	MTU       int      `json:"mtu"`
	Up        bool     `json:"up"`
	Loopback  bool     `json:"loopback"`
	Multicast bool     `json:"multicast"`
	Addrs     []string `json:"addrs"`
}

func installNetworkInfo(p NetworkInfo) {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) { return interfacesFrom(p) })
	refreshNetworkInfo(p)
}

func refreshNetworkInfo(p NetworkInfo) {
	netmon.UpdateLastKnownDefaultRouteInterface(p.DefaultInterface())
	netmon.UpdateLastKnownDefaultGateway(p.DefaultGateway())
}

func interfacesFrom(p NetworkInfo) ([]netmon.Interface, error) {
	var in []ifaceInfo
	if err := json.Unmarshal([]byte(p.Interfaces()), &in); err != nil {
		return nil, err
	}
	out := make([]netmon.Interface, 0, len(in))
	for _, i := range in {
		var flags net.Flags
		if i.Up {
			flags |= net.FlagUp | net.FlagRunning
		}
		if i.Loopback {
			flags |= net.FlagLoopback
		} else {
			flags |= net.FlagBroadcast
		}
		if i.Multicast {
			flags |= net.FlagMulticast
		}
		alt := []net.Addr{} // non-nil, so netmon never falls back to the blocked kernel query
		for _, a := range i.Addrs {
			pfx, err := netip.ParsePrefix(a)
			if err != nil {
				continue
			}
			addr := pfx.Addr()
			alt = append(alt, &net.IPNet{IP: net.IP(addr.AsSlice()), Mask: net.CIDRMask(pfx.Bits(), addr.BitLen())})
		}
		out = append(out, netmon.Interface{
			Interface: &net.Interface{Index: i.Index, MTU: i.MTU, Name: i.Name, Flags: flags},
			AltAddrs:  alt,
		})
	}
	return out, nil
}
