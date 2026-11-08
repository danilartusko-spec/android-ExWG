// Local-only test fixture, never packaged in the Android application.
package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"flag"
	"fmt"
	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	awgnet "github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"golang.org/x/crypto/curve25519"
	"io"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"strings"
)

func main() {
	generation := flag.String("generation", "3.1", "AWG generation: 2.1, 3.0 or 3.1")
	flag.Parse()
	if *generation != "2.1" && *generation != "3.0" && *generation != "3.1" {
		panic("unsupported test generation")
	}
	awgUapi := "jc=4\njmin=40\njmax=70\ns1=32\ns2=64\ns3=16\ns4=16\nh1=101\nh2=202\nh3=303\nh4=404\n"
	awgConfig := "Jc = 4\nJmin = 40\nJmax = 70\nS1 = 32\nS2 = 64\nS3 = 16\nS4 = 16\nH1 = 101\nH2 = 202\nH3 = 303\nH4 = 404\n"
	if *generation != "2.1" {
		var hpk [32]byte
		rand.Read(hpk[:])
		awgUapi += "header_protection_key=" + hex.EncodeToString(hpk[:]) + "\n"
		awgConfig += "HeaderProtectionKey = " + base64.StdEncoding.EncodeToString(hpk[:]) + "\n"
	}
	if *generation == "3.1" {
		awgUapi += "random_trailers=true\ndisable_cookies=true\n"
		awgConfig += "RandomTrailers = on\nDisableCookies = on\n"
	}
	var serverKey, clientKey [32]byte
	rand.Read(serverKey[:])
	rand.Read(clientKey[:])
	serverPub, _ := curve25519.X25519(serverKey[:], curve25519.Basepoint)
	clientPub, _ := curve25519.X25519(clientKey[:], curve25519.Basepoint)
	tun, network, err := awgnet.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.80.0.1")}, nil, 1280)
	if err != nil {
		panic(err)
	}
	d := device.NewDevice(tun, conn.NewStdNetBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer d.Close()
	if err = d.IpcSet(awgUapi + fmt.Sprintf("private_key=%s\nlisten_port=0\npublic_key=%s\nallowed_ip=10.80.0.2/32\n", hex.EncodeToString(serverKey[:]), hex.EncodeToString(clientPub))); err != nil {
		panic(err)
	}
	if err = d.Up(); err != nil {
		panic(err)
	}
	stats, _ := d.IpcGet()
	port := ""
	for _, line := range strings.Split(stats, "\n") {
		if strings.HasPrefix(line, "listen_port=") {
			port = strings.TrimPrefix(line, "listen_port=")
		}
	}
	listener, err := network.ListenTCPAddrPort(netip.MustParseAddrPort("10.80.0.1:8080"))
	if err != nil {
		panic(err)
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
	udpTunnel, err := network.ListenUDPAddrPort(netip.MustParseAddrPort("10.80.0.1:8081"))
	if err != nil {
		panic(err)
	}
	defer udpTunnel.Close()
	go echoUDP(udpTunnel)
	directTCP, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		panic(err)
	}
	defer directTCP.Close()
	go func() {
		for {
			c, err := directTCP.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	directUDP, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		panic(err)
	}
	defer directUDP.Close()
	// Darwin's default UDP send buffer is smaller than the 16 KiB regression
	// datagram. Set this socket's buffer; never change a host-wide sysctl.
	if err := directUDP.(*net.UDPConn).SetWriteBuffer(256 << 10); err != nil {
		panic(err)
	}
	go echoUDP(directUDP)
	fmt.Printf("FIXTURE_DIRECT_TCP=%d\nFIXTURE_DIRECT_UDP=%d\n", directTCP.Addr().(*net.TCPAddr).Port, directUDP.LocalAddr().(*net.UDPAddr).Port)
	config := fmt.Sprintf("[Interface]\nPrivateKey = %s\nAddress = 10.80.0.2/32\nMTU = 1280\n%s[Peer]\nPublicKey = %s\nEndpoint = 10.0.2.2:%s\nAllowedIPs = 0.0.0.0/0\n", base64.StdEncoding.EncodeToString(clientKey[:]), awgConfig, base64.StdEncoding.EncodeToString(serverPub), port)
	fmt.Println("FIXTURE_CONFIG=" + base64.StdEncoding.EncodeToString([]byte(config)))
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt)
	<-stop
}

func echoUDP(c net.PacketConn) {
	buf := make([]byte, 65535)
	for {
		n, addr, err := c.ReadFrom(buf)
		if err != nil {
			return
		}
		if _, err := c.WriteTo(buf[:n], addr); err != nil {
			fmt.Printf("FIXTURE_UDP_WRITE bytes=%d error=%v\n", n, err)
		}
	}
}
