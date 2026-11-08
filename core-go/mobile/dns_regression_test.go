package mobile

import (
	"context"
	"crypto/x509"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"golang.org/x/net/dns/dnsmessage"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"nexusandroid/internal/routing"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func dnsTestPolicy(t *testing.T, source string) *policy {
	t.Helper()
	profile, err := routing.ParseProfile(source)
	if err != nil {
		t.Fatal(err)
	}
	matcher, err := routing.CompileMatcher(profile, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	return &policy{profile: profile, matcher: matcher, dns: newDNSState(), overrides: routing.Overrides{DNS: make(map[string][]string)}}
}
func dnsTestQuery(t *testing.T, domain string, typ dnsmessage.Type) []byte {
	t.Helper()
	name, err := dnsmessage.NewName(domain + ".")
	if err != nil {
		t.Fatal(err)
	}
	msg := dnsmessage.Message{Header: dnsmessage.Header{ID: 123, RecursionDesired: true}, Questions: []dnsmessage.Question{{Name: name, Type: typ, Class: dnsmessage.ClassINET}}}
	wire, err := msg.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return wire
}
func dnsTestResponse(raw []byte, ip string) []byte {
	var msg dnsmessage.Message
	_ = msg.Unpack(raw)
	msg.Response = true
	msg.RecursionAvailable = true
	address := netip.MustParseAddr(ip)
	q := msg.Questions[0]
	header := dnsmessage.ResourceHeader{Name: q.Name, Type: q.Type, Class: q.Class, TTL: 60}
	if address.Is4() {
		msg.Answers = []dnsmessage.Resource{{Header: header, Body: &dnsmessage.AResource{A: address.As4()}}}
	} else {
		msg.Answers = []dnsmessage.Resource{{Header: header, Body: &dnsmessage.AAAAResource{AAAA: address.As16()}}}
	}
	wire, _ := msg.Pack()
	return wire
}

func TestDNSHTTP2CacheAndConcurrentQueries(t *testing.T) {
	var requests atomic.Int64
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.ProtoMajor != 2 {
			t.Errorf("regression: DoH uses %s instead of HTTP/2", r.Proto)
			http.Error(w, "requires h2", 400)
			return
		}
		raw, _ := io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/dns-message")
		w.Write(dnsTestResponse(raw, "203.0.113.8"))
	}))
	server.EnableHTTP2 = true
	server.StartTLS()
	defer server.Close()
	p := dnsTestPolicy(t, `{"Name":"h2","GlobalProxy":false}`)
	defer p.dns.close()
	p.overrides.DNS["dns-direct"] = []string{server.URL + "/dns-query 127.0.0.1"}
	e := &Engine{ctx: context.Background(), protector: testProtector{}, debug: &debugLog{}}
	endpoint, _ := parseDNSEndpoint(p.overrides.DNS["dns-direct"][0])
	addr := netip.MustParseAddrPort(server.Listener.Addr().String())
	client := e.dnsHTTPClient(p, endpoint, addr, routing.ActionDirect)
	pool := x509.NewCertPool()
	pool.AddCert(server.Certificate())
	client.Transport.(*http.Transport).TLSClientConfig.RootCAs = pool
	var wg sync.WaitGroup
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func(id uint16) {
			defer wg.Done()
			raw := dnsTestQuery(t, "example.com", dnsmessage.TypeA)
			binary.BigEndian.PutUint16(raw, id)
			reply, err := e.exchangeDNS(context.Background(), p, raw, routing.ActionDirect)
			if err != nil {
				t.Error(err)
				return
			}
			if binary.BigEndian.Uint16(reply) != id {
				t.Error("coalesced DNS ID mismatch")
			}
		}(uint16(i))
	}
	wg.Wait()
	if requests.Load() != 1 {
		t.Fatalf("identical concurrent queries = %d upstream requests", requests.Load())
	}
	if _, err := e.exchangeDNS(context.Background(), p, dnsTestQuery(t, "example.com", dnsmessage.TypeA), routing.ActionDirect); err != nil {
		t.Fatal(err)
	}
	if requests.Load() != 1 || e.dnsCacheHits.Load() == 0 {
		t.Fatal("TTL cache not used")
	}
}

func TestRealAndFakeDNSRespectProfileAndIPv6Capability(t *testing.T) {
	for _, fake := range []bool{false, true} {
		p := dnsTestPolicy(t, fmt.Sprintf(`{"Name":"mode","GlobalProxy":true,"FakeDNS":%t,"DnsHosts":{"echo.example":"203.0.113.9","v6.example":"2001:db8::9"}}`, fake))
		e := &Engine{ctx: context.Background(), fakeByName: make(map[string]netip.Addr), nameByFake: make(map[netip.Addr]string), debug: &debugLog{}}
		e.policy.Store(p)
		var response dnsmessage.Message
		if err := response.Unpack(e.answerDNS(dnsTestQuery(t, "echo.example", dnsmessage.TypeA))); err != nil {
			t.Fatal(err)
		}
		ip := netip.AddrFrom4(response.Answers[0].Body.(*dnsmessage.AResource).A)
		if fake != netip.MustParsePrefix("198.18.0.0/15").Contains(ip) {
			t.Fatalf("FakeDNS=%t address=%s", fake, ip)
		}
		if !fake {
			if name, ambiguous := e.knownDomain(ip); name != "echo.example" || ambiguous {
				t.Fatal(name, ambiguous)
			}
		}
		if !fake {
			response.Unpack(e.answerDNS(dnsTestQuery(t, "v6.example", dnsmessage.TypeAAAA)))
			if len(response.Answers) != 0 {
				t.Fatal("IPv6-only AWG path must not advertise unusable AAAA")
			}
		}
	}
}

func TestSharedIPDoesNotInventUniqueDomain(t *testing.T) {
	p := dnsTestPolicy(t, `{"Name":"shared","GlobalProxy":true,"DirectSites":["domain:a.example"]}`)
	e := &Engine{}
	for _, name := range []string{"a.example", "b.example"} {
		e.rememberDNS(p, name, dnsTestResponse(dnsTestQuery(t, name, dnsmessage.TypeA), "203.0.113.4"))
	}
	if name, ambiguous := e.knownDomain(netip.MustParseAddr("203.0.113.4")); !ambiguous || name != "" {
		t.Fatal("shared IP incorrectly attributed", name, ambiguous)
	}
}

func TestDNSFallbackAndUDPTruncation(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	udp, err := net.ListenPacket("udp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	var tcpRequests atomic.Int64
	go func() {
		for {
			c, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				var size [2]byte
				if _, err := io.ReadFull(c, size[:]); err != nil {
					return
				}
				raw := make([]byte, int(binary.BigEndian.Uint16(size[:])))
				if _, err := io.ReadFull(c, raw); err != nil {
					return
				}
				tcpRequests.Add(1)
				reply := dnsTestResponse(raw, "203.0.113.7")
				c.Write(append([]byte{byte(len(reply) >> 8), byte(len(reply))}, reply...))
			}()
		}
	}()
	go func() {
		buf := make([]byte, 4096)
		for {
			n, addr, err := udp.ReadFrom(buf)
			if err != nil {
				return
			}
			var msg dnsmessage.Message
			msg.Unpack(buf[:n])
			msg.Response = true
			msg.Truncated = true
			wire, _ := msg.Pack()
			udp.WriteTo(wire, addr)
		}
	}()
	p := dnsTestPolicy(t, `{"Name":"fallback","GlobalProxy":false}`)
	defer p.dns.close()
	// Invalid TLS on an available local TCP endpoint fails promptly.
	p.overrides.DNS["dns-direct"] = []string{"https://127.0.0.1:1/dns-query"}
	p.overrides.DNS["dns-direct-fallback"] = []string{"udp://" + listener.Addr().String()}
	e := &Engine{ctx: context.Background(), protector: testProtector{}, debug: &debugLog{}}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	reply, err := e.exchangeDNS(ctx, p, dnsTestQuery(t, "example.com", dnsmessage.TypeA), routing.ActionDirect)
	if err != nil {
		t.Fatal(err)
	}
	var msg dnsmessage.Message
	msg.Unpack(reply)
	if msg.Truncated || len(msg.Answers) != 1 || tcpRequests.Load() != 1 {
		t.Fatal("UDP->TCP fallback failed", msg)
	}
	if !strings.Contains(e.DrainLogs(), "fallback=true") {
		t.Fatal("fallback not diagnosed")
	}
}

func TestDNSSettingsUnknownFieldsAndCacheAging(t *testing.T) {
	p := dnsTestPolicy(t, `{"Name":"DoU","DomesticDNSType":"DoU","DomesticDNSIP":"77.88.8.8","FutureOption":{"enabled":true}}`)
	ep, err := dnsEndpoints(p, routing.ActionDirect)
	if err != nil || ep[0].URL != "udp://77.88.8.8" {
		t.Fatal(ep, err)
	}
	wire, err := NormalizeProfile(`{"Name":"future","FutureOption":{"enabled":true}}`)
	if err != nil || !strings.Contains(wire, "FutureOption") {
		t.Fatal(wire, err)
	}
	var decoded map[string]any
	json.Unmarshal([]byte(wire), &decoded)
	if decoded["FutureOption"] == nil {
		t.Fatal("unknown field dropped")
	}
	for _, bad := range []string{"http://8.8.8.8/dns-query", "https://user:password@example.com/dns-query 8.8.8.8", "https://example.com/dns-query"} {
		if _, err := parseDNSEndpoint(bad); err == nil {
			t.Fatal("accepted invalid endpoint", bad)
		}
	}
	if epGoogle, err := parseDNSEndpoint("https://dns.google/dns-query"); err != nil || epGoogle.Bootstrap != "8.8.8.8" {
		t.Fatalf("expected known bootstrap IP 8.8.8.8 for dns.google, got %#v (err=%v)", epGoogle, err)
	}
	explained, err := ExplainRoute("", "direct domain:example.com", "https://sub.example.com/path")
	if err != nil || !strings.Contains(explained, `"action":"direct"`) || !strings.Contains(explained, `"domain":"sub.example.com"`) {
		t.Fatalf("unexpected ExplainRoute output: %s (err=%v)", explained, err)
	}
	reply := dnsTestResponse(dnsTestQuery(t, "example.com", dnsmessage.TypeA), "203.0.113.3")
	now := time.Now()
	aged := cachedDNSReply(dnsCacheEntry{wire: reply, created: now.Add(-10 * time.Second)}, 321, now)
	var msg dnsmessage.Message
	msg.Unpack(aged)
	if msg.ID != 321 || msg.Answers[0].Header.TTL != 50 {
		t.Fatal("TTL/id not adjusted")
	}
}

func TestPublicResourceDownloadGuards(t *testing.T) {
	for _, ip := range []string{"127.0.0.1", "::ffff:127.0.0.1", "192.168.1.1", "10.0.0.1", "100.64.0.1", "198.18.0.1", "fd00::1", "::1", "169.254.169.254"} {
		if publicResourceIP(netip.MustParseAddr(ip)) {
			t.Fatal("private resource allowed", ip)
		}
	}
	if !publicResourceIP(netip.MustParseAddr("77.88.8.8")) {
		t.Fatal("public rejected")
	}
}

func TestDNSHostsStaticAnswerRemembersDomainAndRoutesDirect(t *testing.T) {
	p := dnsTestPolicy(t, `{"Name":"nalog","GlobalProxy":true,"DirectSites":["domain:ru"],"DnsHosts":{"lknpd.nalog.ru":"213.24.64.181"}}`)
	e := &Engine{ctx: context.Background(), fakeByName: make(map[string]netip.Addr), nameByFake: make(map[netip.Addr]string), debug: &debugLog{}}
	e.policy.Store(p)

	rawReply := e.answerDNS(dnsTestQuery(t, "lknpd.nalog.ru", dnsmessage.TypeA))
	var msg dnsmessage.Message
	if err := msg.Unpack(rawReply); err != nil {
		t.Fatal(err)
	}
	if len(msg.Answers) != 1 {
		t.Fatalf("answers = %v, want static DnsHosts answer", msg.Answers)
	}
	ip := netip.AddrFrom4(msg.Answers[0].Body.(*dnsmessage.AResource).A)
	if ip.String() != "213.24.64.181" {
		t.Fatalf("ip = %s, want 213.24.64.181", ip)
	}
	domain, ambiguous := e.knownDomain(ip)
	if domain != "lknpd.nalog.ru" || ambiguous {
		t.Fatalf("knownDomain(%s) = (%q, %t), want (lknpd.nalog.ru, false)", ip, domain, ambiguous)
	}
	if action := p.matcher.Route(domain, ip); action != routing.ActionDirect {
		t.Fatalf("Route(%s, %s) = %s, want direct", domain, ip, action)
	}
}

func TestAnswerDNSShortCircuitsAAAAWhenIPv6Unavailable(t *testing.T) {
	p := dnsTestPolicy(t, `{"Name":"no-v6","GlobalProxy":true,"RemoteDNSType":"DoU","RemoteDNSIP":"127.0.0.1:1","DomesticDNSType":"DoU","DomesticDNSIP":"127.0.0.1:1"}`)
	e := &Engine{ctx: context.Background(), fakeByName: make(map[string]netip.Addr), nameByFake: make(map[netip.Addr]string), debug: &debugLog{}}
	e.directIPv6Unavailable.Store(true)
	e.proxyIPv6 = false
	e.policy.Store(p)

	rawReply := e.answerDNS(dnsTestQuery(t, "proxy.example", dnsmessage.TypeAAAA))
	var msg dnsmessage.Message
	if err := msg.Unpack(rawReply); err != nil {
		t.Fatal(err)
	}
	if msg.RCode != dnsmessage.RCodeSuccess || len(msg.Answers) != 0 {
		t.Fatalf("expected immediate NODATA for AAAA when IPv6 is unavailable, got rcode=%v answers=%v", msg.RCode, msg.Answers)
	}
}

func TestExchangeDNSStaleWhileRevalidate(t *testing.T) {
	var requests atomic.Int64
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		count := requests.Add(1)
		raw, _ := io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/dns-message")
		ip := "203.0.113.10"
		if count > 1 {
			ip = "203.0.113.11"
		}
		w.Write(dnsTestResponse(raw, ip))
	}))
	server.EnableHTTP2 = true
	server.StartTLS()
	defer server.Close()

	p := dnsTestPolicy(t, `{"Name":"swr","GlobalProxy":false}`)
	defer p.dns.close()
	p.overrides.DNS["dns-direct"] = []string{server.URL + "/dns-query 127.0.0.1"}
	e := &Engine{ctx: context.Background(), protector: testProtector{}, debug: &debugLog{}}
	endpoint, _ := parseDNSEndpoint(p.overrides.DNS["dns-direct"][0])
	addr := netip.MustParseAddrPort(server.Listener.Addr().String())
	client := e.dnsHTTPClient(p, endpoint, addr, routing.ActionDirect)
	pool := x509.NewCertPool()
	pool.AddCert(server.Certificate())
	client.Transport.(*http.Transport).TLSClientConfig.RootCAs = pool

	query := dnsTestQuery(t, "swr.example", dnsmessage.TypeA)
	reply1, err := e.exchangeDNS(context.Background(), p, query, routing.ActionDirect)
	if err != nil {
		t.Fatal(err)
	}
	key := string(routing.ActionDirect) + string(query[2:])
	p.dns.mu.Lock()
	entry := p.dns.cache[key]
	entry.created = time.Now().Add(-70 * time.Second)
	entry.expires = time.Now().Add(-10 * time.Second)
	entry.staleUntil = time.Now().Add(4 * time.Minute)
	p.dns.cache[key] = entry
	p.dns.mu.Unlock()

	reply2, err := e.exchangeDNS(context.Background(), p, query, routing.ActionDirect)
	if err != nil {
		t.Fatal(err)
	}
	var msg2 dnsmessage.Message
	_ = msg2.Unpack(reply2)
	if len(msg2.Answers) != 1 || netip.AddrFrom4(msg2.Answers[0].Body.(*dnsmessage.AResource).A).String() != "203.0.113.10" {
		t.Fatalf("expected immediate stale answer 203.0.113.10, got %+v", msg2.Answers)
	}
	if msg2.Answers[0].Header.TTL != 15 {
		t.Fatalf("expected stale answer TTL=15, got %d", msg2.Answers[0].Header.TTL)
	}
	_ = reply1
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && requests.Load() < 2 {
		time.Sleep(20 * time.Millisecond)
	}
	if requests.Load() != 2 {
		t.Fatalf("expected background revalidation request, got requests=%d", requests.Load())
	}
}

func TestLoadPolicyCachesCompiledMatcher(t *testing.T) {
	p1, err := loadPolicy("", "direct domain:cache.example")
	if err != nil {
		t.Fatal(err)
	}
	p2, err := loadPolicy("", "direct domain:cache.example")
	if err != nil {
		t.Fatal(err)
	}
	if p1.matcher != p2.matcher || p1.profile != p2.profile {
		t.Fatal("expected loadPolicy to reuse cached compiled matcher and profile")
	}
	if p1.dns == p2.dns {
		t.Fatal("expected each policy instance to get its own dnsState")
	}
	p3, err := loadPolicy("", "block domain:cache.example")
	if err != nil {
		t.Fatal(err)
	}
	if p3.matcher == p1.matcher {
		t.Fatal("expected loadPolicy to recompile matcher when overrides change")
	}
}
