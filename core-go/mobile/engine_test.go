package mobile

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	awgnet "github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"golang.org/x/crypto/curve25519"
	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/sys/unix"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type testProtector struct{}

func (testProtector) Protect(int) bool { return true }
func keys(t *testing.T) (string, string) {
	t.Helper()
	var k [32]byte
	rand.Read(k[:])
	p, err := curve25519.X25519(k[:], curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(k[:]), hex.EncodeToString(p)
}

func TestActualAWGHandshakeAndRoutedTCPUDP(t *testing.T) {
	// Exercise actual AWG obfuscation and header encryption, not only timing flags.
	base := "jc=4\njmin=40\njmax=70\ns1=32\ns2=64\ns3=16\ns4=16\nh1=101\nh2=202\nh3=303\nh4=404\n"
	protected := base + "header_protection_key=" + strings.Repeat("11", 32) + "\n"
	for _, generation := range []struct{ name, extra string }{
		{"2.1", base}, {"3.0", protected + "rekey_after_time=60\n"}, {"3.1", protected + "random_trailers=true\ndisable_cookies=true\n"},
	} {
		t.Run(generation.name, func(t *testing.T) { testActualTunnel(t, generation.extra) })
	}
}

func testActualTunnel(t *testing.T, extra string) {
	serverKey, serverPub := keys(t)
	clientKey, clientPub := keys(t)
	serverTun, serverNet, err := awgnet.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.80.0.1")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	server := device.NewDevice(serverTun, conn.NewStdNetBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer server.Close()
	if err = server.IpcSet(extra + fmt.Sprintf("private_key=%s\nlisten_port=0\npublic_key=%s\nallowed_ip=10.80.0.2/32\n", serverKey, clientPub)); err != nil {
		t.Fatal(err)
	}
	if err = server.Up(); err != nil {
		t.Fatal(err)
	}
	stats, _ := server.IpcGet()
	port := ""
	for _, l := range strings.Split(stats, "\n") {
		if strings.HasPrefix(l, "listen_port=") {
			port = strings.TrimPrefix(l, "listen_port=")
		}
	}
	listener, err := serverNet.ListenTCPAddrPort(netip.MustParseAddrPort("10.80.0.1:8080"))
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			c, err := listener.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	udpServer, err := serverNet.ListenUDPAddrPort(netip.MustParseAddrPort("10.80.0.1:8081"))
	if err != nil {
		t.Fatal(err)
	}
	defer udpServer.Close()
	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := udpServer.ReadFrom(buf)
			if err != nil {
				return
			}
			udpServer.WriteTo(buf[:n], addr)
		}
	}()
	direct, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer direct.Close()
	go func() {
		for {
			c, err := direct.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	dir := t.TempDir()
	profile := `{"Name":"test","GlobalProxy":true,"FakeDNS":true,"DirectSites":["domain:direct.example"],"BlockSites":["domain:blocked.example"],"DnsHosts":{"echo.example":"10.80.0.1","direct.example":"127.0.0.1"}}`
	if err = os.WriteFile(filepath.Join(dir, "profile.json"), []byte(profile), 0600); err != nil {
		t.Fatal(err)
	}
	pair, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	unix.SetNonblock(pair[0], true)
	unix.SetNonblock(pair[1], true)
	engine, err := Start(pair[0], extra+fmt.Sprintf("private_key=%s\npublic_key=%s\nendpoint=127.0.0.1:%s\nallowed_ip=0.0.0.0/0\n", clientKey, serverPub, port), "10.80.0.2/32", 1280, dir, "", testProtector{})
	if err != nil {
		t.Fatal(err)
	}
	defer engine.Close()
	clientTun, clientNet, err := awgnet.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.111.0.1")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	bridge := os.NewFile(uintptr(pair[1]), "test-tun")
	defer bridge.Close()
	// netstack's test client represents Android apps, sending actual IP packets.
	go func() {
		buf := [][]byte{make([]byte, 65535)}
		sizes := []int{0}
		for {
			_, err := clientTun.Read(buf, sizes, 0)
			if err != nil {
				return
			}
			if _, err = bridge.Write(buf[0][:sizes[0]]); err != nil {
				return
			}
		}
	}()
	go func() {
		buf := make([]byte, 65535)
		for {
			n, err := bridge.Read(buf)
			if err != nil {
				return
			}
			clientTun.Write([][]byte{buf[:n]}, 0)
		}
	}()
	defer clientTun.Close()
	dnsConn, err := clientNet.DialUDPAddrPort(netip.AddrPort{}, netip.MustParseAddrPort("10.111.0.2:53"))
	if err != nil {
		t.Fatal(err)
	}
	defer dnsConn.Close()
	lookup := func(domain string) (netip.Addr, dnsmessage.RCode) {
		t.Helper()
		name, _ := dnsmessage.NewName(domain + ".")
		query := dnsmessage.Message{Header: dnsmessage.Header{ID: 42, RecursionDesired: true}, Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}
		raw, _ := query.Pack()
		dnsConn.SetDeadline(time.Now().Add(5 * time.Second))
		if _, err := dnsConn.Write(raw); err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, 4096)
		n, err := dnsConn.Read(buf)
		if err != nil {
			t.Fatal(err)
		}
		var msg dnsmessage.Message
		if err = msg.Unpack(buf[:n]); err != nil {
			t.Fatal(err)
		}
		if len(msg.Answers) > 0 {
			return netip.AddrFrom4(msg.Answers[0].Body.(*dnsmessage.AResource).A), msg.RCode
		}
		return netip.Addr{}, msg.RCode
	}
	fake, rcode := lookup("echo.example")
	if rcode != dnsmessage.RCodeSuccess || !fake.IsValid() {
		t.Fatal("fake DNS failed")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	tcpConn, err := clientNet.DialContextTCPAddrPort(ctx, netip.AddrPortFrom(fake, 8080))
	if err != nil {
		t.Fatal(err)
	}
	echo := func(c net.Conn) {
		t.Helper()
		defer c.Close()
		c.SetDeadline(time.Now().Add(10 * time.Second))
		if _, err := c.Write([]byte("nexus-packet-test")); err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, 17)
		n, err := c.Read(buf)
		if err != nil || string(buf[:n]) != "nexus-packet-test" {
			t.Fatalf("echo failed: %v %q", err, buf[:n])
		}
	}
	echo(tcpConn)
	udpConn, err := clientNet.DialUDPAddrPort(netip.AddrPort{}, netip.AddrPortFrom(fake, 8081))
	if err != nil {
		t.Fatal(err)
	}
	echo(udpConn)
	fakeDirect, _ := lookup("direct.example")
	directConn, err := clientNet.DialContextTCPAddrPort(ctx, netip.AddrPortFrom(fakeDirect, uint16(direct.Addr().(*net.TCPAddr).Port)))
	if err != nil {
		t.Fatal(err)
	}
	echo(directConn)
	if _, rcode = lookup("blocked.example"); rcode != dnsmessage.RCodeNameError {
		t.Fatal("BlockSites must return NXDOMAIN")
	}
	var counters map[string]any
	json.Unmarshal([]byte(engine.Statistics()), &counters)
	if counters["handshake"].(float64) <= 0 || counters["rx"].(float64) <= 0 || counters["proxyFlows"].(float64) < 2 || counters["directFlows"].(float64) < 1 {
		t.Fatalf("missing real counters: %v", counters)
	}
	if strings.Contains(engine.Statistics(), clientKey) || strings.Contains(engine.Statistics(), serverPub) {
		t.Fatal("statistics leaked keys")
	}
	if err := engine.UpdateRules(dir, "block domain:echo.example"); err != nil {
		t.Fatal(err)
	}
	if _, rcode = lookup("echo.example"); rcode != dnsmessage.RCodeNameError {
		t.Fatal("live policy update did not block")
	}
}

func TestInvalidBundleAndOfflineFallback(t *testing.T) {
	if err := ValidateBundle("", ""); err != nil {
		t.Fatal(err)
	}
	if action, err := Classify("", "", "пример.рф", ""); err != nil || action != "direct" {
		t.Fatalf("builtin direct: %s %v", action, err)
	}
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "profile.json"), []byte(`{"Name":"bad","ProxySites":["geosite:missing"]}`), 0600)
	if ValidateBundle(dir, "") == nil {
		t.Fatal("missing geo data must not silently drop rules")
	}
	if _, err := NormalizeProfile(`{"GlobalProxy":true}`); err == nil {
		t.Fatal("mandatory name not validated")
	}
}
