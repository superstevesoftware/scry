//go:build !android

package core

// Off Android the operating system answers these questions itself, so there is nothing to install.
func installNetworkInfo(NetworkInfo) {}

func refreshNetworkInfo(NetworkInfo) {}
