package mobile

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"io"
	"math/rand"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"nexusandroid/internal/routing"
)

func TestSniffHTTPAndTLSFragmentation(t *testing.T) {
	for _, test := range []struct{ data, name string }{
		{"GET / HTTP/1.1\r\nHost: API.Example.test:443\r\n\r\n", "api.example.test"},
		{"GET / HTTP/1.1\r\nHost: first.test\r\nHost: second.test\r\n\r\n", ""},
		{"GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", ""},
		{"SSH-2.0-server\r\n", ""},
	} {
		name, _ := sniffDomain([]byte(test.data))
		if name != test.name {
			t.Fatalf("%q => %q", test.data, name)
		}
	}
	client, server := net.Pipe()
	defer server.Close()
	go func() {
		defer client.Close()
		_ = tls.Client(client, &tls.Config{ServerName: "app.example.test", MinVersion: tls.VersionTLS12}).Handshake()
	}()
	_ = server.SetDeadline(time.Now().Add(time.Second))
	header := make([]byte, 5)
	if _, err := io.ReadFull(server, header); err != nil {
		t.Fatal(err)
	}
	body := make([]byte, int(binary.BigEndian.Uint16(header[3:])))
	if _, err := io.ReadFull(server, body); err != nil {
		t.Fatal(err)
	}
	wire := append(header, body...)
	if name, _ := sniffDomain(wire); name != "app.example.test" {
		t.Fatalf("real Go ClientHello => %q", name)
	}
	for n := 0; n < len(wire); n++ {
		if name, _ := sniffDomain(wire[:n]); name != "" {
			t.Fatalf("premature SNI at %d", n)
		}
	}
	var fragmented []byte
	for start := 0; start < len(body); {
		end := min(start+17, len(body))
		n := end - start
		fragmented = append(fragmented, 22, 3, 3, byte(n>>8), byte(n))
		fragmented = append(fragmented, body[start:end]...)
		start = end
	}
	if name, _ := sniffDomain(fragmented); name != "app.example.test" {
		t.Fatalf("fragmented => %q", name)
	}
}

func TestSniffPreservesBytesAndServerFirstTimeout(t *testing.T) {
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	payload := []byte("GET / HTTP/1.1\r\nHost: app.example.test\r\n\r\n" + strings.Repeat("body", 10000))
	go func() { _, _ = right.Write(payload); right.Close() }()
	first, name := sniffTCP(left)
	rest, err := io.ReadAll(left)
	if err != nil || name != "app.example.test" || !bytes.Equal(append(first, rest...), payload) {
		t.Fatal("sniffer changed/lost bytes", err)
	}
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	start := time.Now()
	prefix, name := sniffTCP(a)
	if len(prefix) != 0 || name != "" || time.Since(start) > time.Second {
		t.Fatal("server-first protocol stalled")
	}
	go b.Write([]byte("server speaks first"))
	buf := make([]byte, 19)
	if _, err := io.ReadFull(a, buf); err != nil {
		t.Fatal("deadline not reset", err)
	}
}

func TestSniffMalformedInputBounded(t *testing.T) {
	r := rand.New(rand.NewSource(31))
	for n := 0; n < 4096; n++ {
		data := make([]byte, n%512)
		_, _ = r.Read(data)
		if len(data) > 0 {
			data[0] = 22
		}
		sniffDomain(data)
		clientHelloName(data)
		filterServiceBinding(data, func(netip.Addr) bool { return false })
	}
}

func TestDirectIPv6CapabilityChangesDNSWithoutBypass(t *testing.T) {
	p := dnsTestPolicy(t, `{"Name":"capabilities","GlobalProxy":true,"DirectSites":["domain:direct.example"]}`)
	e := &Engine{debug: &debugLog{}}
	e.SetDirectIPv6(false)
	raw := dnsTestResponse(dnsTestQuery(t, "direct.example", dnsmessage.TypeAAAA), "2001:db8::1")
	var answer dnsmessage.Message
	_ = answer.Unpack(e.rememberDNS(p, "direct.example", raw))
	if len(answer.Answers) != 0 {
		t.Fatal("unavailable physical IPv6 advertised")
	}
	e.SetDirectIPv6(true)
	_ = answer.Unpack(e.rememberDNS(p, "direct.example", raw))
	if len(answer.Answers) != 1 {
		t.Fatal("IPv6 not restored after network change")
	}
	_ = answer.Unpack(e.rememberDNS(p, "proxy.example", raw))
	if len(answer.Answers) != 0 {
		t.Fatal("Proxy IPv6 leaked to Direct")
	}
}

func TestTCPAlternateAddressStaysInSelectedRoute(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		c, err := listener.Accept()
		if err == nil {
			c.Close()
		}
	}()
	p := dnsTestPolicy(t, `{"Name":"alternate","GlobalProxy":false,"DnsHosts":{"app.example":"127.0.0.1"}}`)
	e := &Engine{ctx: context.Background(), protector: testProtector{}, debug: &debugLog{}}
	e.SetDirectIPv6(false)
	port := uint16(listener.Addr().(*net.TCPAddr).Port)
	c, err := e.dialCandidates(context.Background(), p, "tcp", "app.example", netip.MustParseAddr("2001:db8::1"), port, routing.ActionDirect, 1)
	if err != nil {
		t.Fatal("same-route IPv4 fallback failed", err)
	}
	c.Close()
	blocked := dnsTestPolicy(t, `{"Name":"blocked","GlobalProxy":false,"BlockIP":["127.0.0.1/32"],"DnsHosts":{"app.example":"127.0.0.1"}}`)
	if c, err := e.dialCandidates(context.Background(), blocked, "tcp", "app.example", netip.MustParseAddr("2001:db8::1"), port, routing.ActionDirect, 2); err == nil {
		c.Close()
		t.Fatal("fallback ignored Block")
	}
	proxy := dnsTestPolicy(t, `{"Name":"proxy","GlobalProxy":true,"DirectIP":["127.0.0.1/32"],"DnsHosts":{"app.example":"127.0.0.1"}}`)
	if c, err := e.dialCandidates(context.Background(), proxy, "tcp", "app.example", netip.MustParseAddr("2001:db8::1"), port, routing.ActionProxy, 3); err == nil {
		c.Close()
		t.Fatal("Proxy fell back to Direct")
	}
}

func TestHTTPSHintsPreserveECHAndMapIPv4(t *testing.T) {
	// ServiceMode, root target, ipv4hint, ECH (opaque), ipv6hint.
	data := []byte{0, 1, 0, 0, 4, 0, 4, 203, 0, 113, 7, 0, 5, 0, 3, 1, 2, 3, 0, 6, 0, 16}
	v6 := netip.MustParseAddr("2001:db8::7").As16()
	data = append(data, v6[:]...)
	filtered, hints, changed := filterServiceBinding(data, func(ip netip.Addr) bool { return ip.Is4() })
	if !changed || len(hints) != 1 || !bytes.Equal(filtered, data[:18]) {
		t.Fatalf("unexpected hints/ECH filtering: %x %v", filtered, hints)
	}
	name, _ := dnsmessage.NewName("app.example.")
	msg := dnsmessage.Message{Header: dnsmessage.Header{Response: true, AuthenticData: true}, Answers: []dnsmessage.Resource{{Header: dnsmessage.ResourceHeader{Name: name, Type: 65, Class: dnsmessage.ClassINET, TTL: 60}, Body: &dnsmessage.UnknownResource{Type: 65, Data: data}}}}
	wire, _ := msg.Pack()
	p := dnsTestPolicy(t, `{"Name":"hints","GlobalProxy":true}`)
	e := &Engine{}
	_ = msg.Unpack(e.rememberDNS(p, "app.example", wire))
	if msg.AuthenticData {
		t.Fatal("modified response still claims DNSSEC authentication")
	}
	if name, ambiguous := e.knownDomain(netip.MustParseAddr("203.0.113.7")); name != "app.example" || ambiguous {
		t.Fatal("HTTPS hint domain missing")
	}
}
