package mobile

import (
	"nexusandroid/internal/routing"
	"testing"
)

func TestProxyDNSFailuresAreConsecutiveAndRouteSpecific(t *testing.T) {
	e := &Engine{}
	e.recordProxyDNSResult(routing.ActionDirect, false)
	if got := e.proxyDNSFailures.Load(); got != 0 {
		t.Fatalf("Direct DNS must not affect Proxy health: %d", got)
	}
	e.recordProxyDNSResult(routing.ActionProxy, false)
	e.recordProxyDNSResult(routing.ActionProxy, false)
	if got := e.proxyDNSFailures.Load(); got != 2 {
		t.Fatalf("Proxy failures=%d, want 2", got)
	}
	e.recordProxyDNSResult(routing.ActionProxy, true)
	if got := e.proxyDNSFailures.Load(); got != 0 {
		t.Fatalf("successful Proxy DNS must reset failures: %d", got)
	}
}
