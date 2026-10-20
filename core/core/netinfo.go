package core

import "sync"

// NetworkInfo is implemented by the Android app. Since Android 11 an app may not ask the
// kernel for its network interfaces or routes, which Tailscale's library tries to do, so the
// app supplies the same information through the platform's own APIs instead.
type NetworkInfo interface {
	// Interfaces returns a JSON array describing every network interface:
	// [{"name":"wlan0","index":3,"mtu":1500,"up":true,"loopback":false,"multicast":true,
	//   "addrs":["192.168.1.5/24","fe80::1/64"]}]
	Interfaces() string
	// DefaultInterface is the name of the interface carrying the default route ("" if none).
	DefaultInterface() string
	// DefaultGateway is the default route's gateway address ("" if unknown).
	DefaultGateway() string
}

var netInfo struct {
	mu sync.Mutex
	p  NetworkInfo
}

// SetNetworkInfo installs the app's network information source. Call it once at startup,
// before any tunnel is started.
func SetNetworkInfo(p NetworkInfo) {
	netInfo.mu.Lock()
	netInfo.p = p
	netInfo.mu.Unlock()
	installNetworkInfo(p)
}

// NetworkInfoChanged tells the core to re-read the default route and gateway. Call it when
// the device's network changes and before starting a tunnel.
func NetworkInfoChanged() {
	netInfo.mu.Lock()
	p := netInfo.p
	netInfo.mu.Unlock()
	if p != nil {
		refreshNetworkInfo(p)
	}
}
