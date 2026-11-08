package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	awgnet "github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
	"nexusandroid/internal/routing"
)

// Protector is implemented by Kotlin VpnService. All physical sockets MUST be
// protected before use, including AWG sockets reopened after network changes.
type Protector interface{ Protect(fd int) bool }

type protectedBind struct {
	conn.Bind
	protector Protector
}

func (b *protectedBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	fns, p, err := b.Bind.Open(port)
	if err != nil {
		return nil, 0, err
	}
	peek, ok := b.Bind.(conn.PeekLookAtSocketFd)
	if !ok {
		b.Close()
		return nil, 0, errors.New("AWG socket protection unavailable")
	}
	for _, get := range []func() (int, error){peek.PeekLookAtSocketFd4, peek.PeekLookAtSocketFd6} {
		fd, e := get()
		if e == nil && fd >= 0 && !b.protector.Protect(fd) {
			b.Close()
			return nil, 0, errors.New("AWG socket protection failed")
		}
	}
	return fns, p, nil
}

type Engine struct {
	ctx                   context.Context
	cancel                context.CancelFunc
	file                  *os.File
	device                *device.Device
	peer                  *device.Peer
	debug                 *debugLog
	proxy                 *awgnet.Net
	ingress               *stack.Stack
	link                  *channel.Endpoint
	policy                atomic.Pointer[policy]
	protector             Protector
	closed                atomic.Bool
	failed                atomic.Bool
	networkMu             sync.Mutex
	mu                    sync.Mutex
	flows                 map[net.Conn]struct{}
	fakeMu                sync.Mutex
	fakeByName            map[string]netip.Addr
	nameByFake            map[netip.Addr]string
	knownIPs              map[netip.Addr]map[string]time.Time
	proxyIPv6             bool
	directIPv6Unavailable atomic.Bool
	dnsCacheHits          atomic.Int64
	flowSequence          atomic.Int64
	queries               atomic.Int64
	dnsErrors             atomic.Int64
	proxyDNSFailures      atomic.Int64
	directFlows           atomic.Int64
	proxyFlows            atomic.Int64
	blockedFlows          atomic.Int64
	flowSlots             chan struct{}
}

// Start takes ownership of a duplicated Android TUN descriptor. Kotlin owns the
// VpnService and foreground notification; no Android lifecycle runs in Go.
func Start(fd int, settings, addresses string, mtu int, directory, overrides string, protector Protector) (*Engine, error) {
	return StartWithNetwork(fd, settings, addresses, mtu, directory, overrides, protector, true)
}

// StartWithNetwork receives physical-network capability before the first DNS
// packet. Both Android TUN families remain captured even on IPv4-only networks.
func StartWithNetwork(fd int, settings, addresses string, mtu int, directory, overrides string, protector Protector, directIPv6 bool) (*Engine, error) {
	// Android TUN must participate in Go netpoll: a blocking descriptor can make
	// Close wait forever for a reader when the phone has no traffic.
	if err := unix.SetNonblock(fd, true); err != nil {
		unix.Close(fd)
		return nil, fmt.Errorf("TUN nonblocking setup failed: %w", err)
	}
	f := os.NewFile(uintptr(fd), "nexus-tun")
	if f == nil {
		return nil, errors.New("TUN descriptor invalid")
	}
	ok := false
	defer func() {
		if !ok {
			f.Close()
		}
	}()
	if protector == nil || mtu < 1280 || mtu > 1500 {
		return nil, errors.New("TUN parameters invalid")
	}
	p, err := loadPolicy(directory, overrides)
	if err != nil {
		return nil, err
	}
	var local []netip.Addr
	for _, value := range strings.Split(addresses, ",") {
		prefix, err := netip.ParsePrefix(strings.TrimSpace(value))
		if err != nil {
			return nil, fmt.Errorf("AWG address invalid: %w", err)
		}
		local = append(local, prefix.Addr())
	}
	nt, proxy, err := awgnet.CreateNetTUN(local, nil, mtu)
	if err != nil {
		return nil, fmt.Errorf("AWG netstack creation failed: %w", err)
	}
	debug := &debugLog{}
	d := device.NewDevice(nt, &protectedBind{conn.NewStdNetBind(), protector}, coreLogger(debug))
	if err := d.IpcSet(settings); err != nil {
		d.Close()
		return nil, fmt.Errorf("AWG IpcSet: %s", errorDetail(err))
	}
	d.DisableSomeRoamingForBrokenMobileSemantics()
	ctx, cancel := context.WithCancel(context.Background())
	e := &Engine{ctx: ctx, cancel: cancel, file: f, device: d, proxy: proxy, protector: protector, flows: make(map[net.Conn]struct{}), fakeByName: make(map[string]netip.Addr), nameByFake: make(map[netip.Addr]string), flowSlots: make(chan struct{}, 512)}
	e.debug = debug
	e.directIPv6Unavailable.Store(!directIPv6)
	for _, address := range local {
		if address.Is6() {
			e.proxyIPv6 = true
		}
	}
	e.logInfo("routing.policy", fmt.Sprintf("name=%s strategy=%s fakeDNS=%t proxyIPv6=%t warnings=%v", p.profile.Name, p.profile.DomainStrategy, bool(p.profile.FakeDNS), e.proxyIPv6, p.profile.Warnings()))
	for _, line := range strings.Split(settings, "\n") {
		if value, found := strings.CutPrefix(line, "public_key="); found {
			var key device.NoisePublicKey
			if key.FromHex(value) == nil {
				e.peer = d.LookupPeer(key)
			}
		}
	}
	e.policy.Store(p)
	if err := e.initIngress(mtu); err != nil {
		e.Close()
		return nil, err
	}
	if err := d.Up(); err != nil {
		e.Close()
		return nil, fmt.Errorf("AWG UDP socket startup failed: %w", err)
	}
	go e.readTun()
	go e.writeTun()
	go e.warmupDNS(p)
	ok = true
	return e, nil
}

func (e *Engine) initIngress(mtu int) error {
	e.ingress = stack.New(stack.Options{NetworkProtocols: []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol}, TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol}})
	e.link = channel.New(1024, uint32(mtu), "")
	if err := e.ingress.CreateNIC(1, e.link); err != nil {
		return fmt.Errorf("TUN stack initialization failed: %s", err.String())
	}
	e.ingress.SetPromiscuousMode(1, true)
	e.ingress.SetSpoofing(1, true)
	e.ingress.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}, {Destination: header.IPv6EmptySubnet, NIC: 1}})
	tf := tcp.NewForwarder(e.ingress, 0, 256, func(r *tcp.ForwarderRequest) {
		select {
		case e.flowSlots <- struct{}{}:
		default:
			e.logInfo("TCP.reject", "flow capacity reached (512)")
			r.Complete(true)
			return
		}
		go func() { defer func() { <-e.flowSlots }(); e.forwardTCP(r) }()
	})
	e.ingress.SetTransportProtocolHandler(tcp.ProtocolNumber, tf.HandlePacket)
	uf := udp.NewForwarder(e.ingress, func(r *udp.ForwarderRequest) bool {
		var q waiter.Queue
		ep, err := r.CreateEndpoint(&q)
		if err != nil {
			return false
		}
		c := gonet.NewUDPConn(&q, ep)
		select {
		case e.flowSlots <- struct{}{}:
		default:
			e.logInfo("UDP.reject", "flow capacity reached (512)")
			c.Close()
			return true
		}
		go func() { defer func() { <-e.flowSlots }(); e.forwardUDP(r.ID(), c) }()
		return true
	})
	e.ingress.SetTransportProtocolHandler(udp.ProtocolNumber, uf.HandlePacket)
	return nil
}

func (e *Engine) readTun() {
	buf := make([]byte, 65535)
	for {
		n, err := e.file.Read(buf)
		if err != nil {
			if !e.closed.Load() {
				e.logError("TUN.read", err)
				e.failed.Store(true)
			}
			return
		}
		if n < 1 {
			continue
		}
		var proto tcpip.NetworkProtocolNumber
		switch buf[0] >> 4 {
		case 4:
			proto = ipv4.ProtocolNumber
		case 6:
			proto = ipv6.ProtocolNumber
		default:
			continue
		}
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte(nil), buf[:n]...))})
		e.link.InjectInbound(proto, pkt)
		pkt.DecRef()
	}
}
func (e *Engine) writeTun() {
	for {
		pkt := e.link.ReadContext(e.ctx)
		if pkt == nil {
			return
		}
		v := pkt.ToView()
		_, err := e.file.Write(v.AsSlice())
		v.Release()
		pkt.DecRef()
		if err != nil {
			if !e.closed.Load() {
				e.logError("TUN.write", err)
				e.failed.Store(true)
			}
			return
		}
	}
}

func (e *Engine) track(c net.Conn) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed.Load() {
		c.Close()
		return false
	}
	e.flows[c] = struct{}{}
	return true
}
func (e *Engine) untrack(c net.Conn) { c.Close(); e.mu.Lock(); delete(e.flows, c); e.mu.Unlock() }
func (e *Engine) trackForPolicy(c net.Conn, expected *policy) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed.Load() || e.policy.Load() != expected {
		c.Close()
		return false
	}
	e.flows[c] = struct{}{}
	return true
}
func (e *Engine) forwardTCP(r *tcp.ForwarderRequest) {
	id := r.ID()
	var q waiter.Queue
	ep, err := r.CreateEndpoint(&q)
	if err != nil {
		r.Complete(true)
		return
	}
	r.Complete(false)
	c := gonet.NewTCPConn(&q, ep)
	if !e.track(c) {
		return
	}
	defer e.untrack(c)
	if id.LocalPort == 53 {
		e.serveDNS(c, true)
		return
	}
	ip, ok := netip.AddrFromSlice(id.LocalAddress.AsSlice())
	if !ok {
		return
	}
	ctx, cancel := context.WithTimeout(e.ctx, 15*time.Second)
	defer cancel()
	prefix, domain := sniffTCP(c)
	generation := e.policy.Load()
	remote, err2 := e.dialTargetDomain(ctx, "tcp", ip, id.LocalPort, domain)
	if err2 != nil {
		e.logError("TCP.dial", err2)
		return
	}
	if !e.trackForPolicy(remote, generation) {
		return
	}
	defer e.untrack(remote)
	if len(prefix) > 0 {
		_ = remote.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if _, err := remote.Write(prefix); err != nil {
			e.logError("TCP.initial.write", err)
			return
		}
		_ = remote.SetWriteDeadline(time.Time{})
	}
	done := make(chan struct{})
	go func() {
		n, err := io.Copy(remote, c)
		e.logInfo("TCP.upload.end", fmt.Sprintf("destination=%s:%d bytes=%d", ip, id.LocalPort, n))
		e.logError(fmt.Sprintf("TCP.upload destination=%s:%d", ip, id.LocalPort), err)
		if closer, ok := remote.(interface{ CloseWrite() error }); ok {
			closer.CloseWrite()
		}
		close(done)
	}()
	n, relayError := io.Copy(c, remote)
	e.logInfo("TCP.download.end", fmt.Sprintf("destination=%s:%d bytes=%d", ip, id.LocalPort, n))
	e.logError(fmt.Sprintf("TCP.download destination=%s:%d", ip, id.LocalPort), relayError)
	c.Close()
	remote.Close()
	<-done
}
func (e *Engine) forwardUDP(id stack.TransportEndpointID, c net.Conn) {
	if !e.track(c) {
		return
	}
	defer e.untrack(c)
	if id.LocalPort == 53 {
		e.serveDNS(c, false)
		return
	}
	ip, ok := netip.AddrFromSlice(id.LocalAddress.AsSlice())
	if !ok {
		return
	}
	ctx, cancel := context.WithTimeout(e.ctx, 15*time.Second)
	defer cancel()
	generation := e.policy.Load()
	r, err := e.dialTarget(ctx, "udp", ip, id.LocalPort)
	if err != nil {
		e.logError("UDP.dial", err)
		return
	}
	if !e.trackForPolicy(r, generation) {
		return
	}
	defer e.untrack(r)
	done := make(chan struct{})
	go func() {
		defer close(done)
		buf := make([]byte, 65535)
		for {
			c.SetReadDeadline(time.Now().Add(60 * time.Second))
			n, err := c.Read(buf)
			if err != nil {
				e.logError(fmt.Sprintf("UDP.client.read destination=%s:%d", ip, id.LocalPort), err)
				return
			}
			r.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if _, err = r.Write(buf[:n]); err != nil {
				e.logError(fmt.Sprintf("UDP.upstream.write destination=%s:%d", ip, id.LocalPort), err)
				return
			}
		}
	}()
	buf := make([]byte, 65535)
	for {
		r.SetReadDeadline(time.Now().Add(60 * time.Second))
		n, err := r.Read(buf)
		if err != nil {
			e.logError(fmt.Sprintf("UDP.upstream.read destination=%s:%d", ip, id.LocalPort), err)
			break
		}
		c.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if _, err = c.Write(buf[:n]); err != nil {
			e.logError(fmt.Sprintf("UDP.client.write destination=%s:%d", ip, id.LocalPort), err)
			break
		}
	}
	c.Close()
	r.Close()
	<-done
}

func (e *Engine) control(_, _ string, raw syscall.RawConn) error {
	protected := false
	if err := raw.Control(func(fd uintptr) { protected = e.protector.Protect(int(fd)) }); err != nil {
		return err
	}
	if !protected {
		return errors.New("socket protection failed")
	}
	return nil
}
func (e *Engine) dial(ctx context.Context, network string, addr netip.AddrPort, action routing.Action) (net.Conn, error) {
	if action == routing.ActionBlock {
		return nil, errors.New("blocked")
	}
	if action == routing.ActionDirect {
		return (&net.Dialer{Timeout: 10 * time.Second, Control: e.control}).DialContext(ctx, network, addr.String())
	}
	if network == "tcp" {
		return e.proxy.DialContextTCPAddrPort(ctx, addr)
	}
	return e.proxy.DialUDPAddrPort(netip.AddrPort{}, addr)
}
func (e *Engine) dialTarget(ctx context.Context, network string, ip netip.Addr, port uint16) (net.Conn, error) {
	return e.dialTargetDomain(ctx, network, ip, port, "")
}

func (e *Engine) dialTargetDomain(ctx context.Context, network string, ip netip.Addr, port uint16, sniffed string) (net.Conn, error) {
	id := e.flowSequence.Add(1)
	original := ip
	e.fakeMu.Lock()
	domain := e.nameByFake[ip]
	e.fakeMu.Unlock()
	if domain == "" && (netip.MustParsePrefix("198.18.0.0/15").Contains(ip) || netip.MustParsePrefix("fd6e:6578:7573::/48").Contains(ip)) {
		return nil, errors.New("expired DNS mapping")
	}
	p := e.policy.Load()
	synthetic := domain != ""
	var ambiguous bool
	if !synthetic {
		domain, ambiguous = e.knownDomain(ip)
		if sniffed != "" {
			domain, ambiguous = sniffed, false
			e.logInfo("FLOW.sniff", fmt.Sprintf("id=%d domain=%s mode=route-only destination=%s", id, domain, ip))
		}
	}
	action := p.matcher.Route(domain, ip)
	if domain != "" {
		if synthetic {
			action = p.matcher.Domain(domain)
		}
	} // Synthetic DNS IPs must never match geoip:private.
	if ambiguous && action != routing.ActionBlock {
		action = e.sharedDNSRoute(p, ip)
	}
	e.logInfo("FLOW.begin", fmt.Sprintf("id=%d network=%s destination=%s:%d domain=%q synthetic=%t ambiguousDNS=%t route=%s strategy=%s", id, network, original, port, domain, synthetic, ambiguous, action, p.profile.DomainStrategy))
	if action == routing.ActionBlock {
		e.blockedFlows.Add(1)
		return nil, fmt.Errorf("flow=%d domain=%s destination=%s:%d route=block", id, domain, original, port)
	}
	if synthetic {
		var err error
		ip, err = e.resolve(ctx, p, domain, action, ip.Is6())
		if err != nil {
			e.dnsErrors.Add(1)
			return nil, fmt.Errorf("flow=%d domain=%s route=%s stage=DNS: %w", id, domain, action, err)
		}
		action = p.matcher.Route(domain, ip)
	}
	if action == routing.ActionBlock {
		e.blockedFlows.Add(1)
		return nil, fmt.Errorf("flow=%d domain=%s ip=%s route=block after DNS", id, domain, ip)
	}
	if action == routing.ActionDirect {
		e.directFlows.Add(1)
	} else {
		e.proxyFlows.Add(1)
	}
	if ip == netip.MustParseAddr("10.111.0.2") && port == 853 {
		return nil, fmt.Errorf("flow=%d Android Private DNS probe: internal resolver serves UDP/TCP 53, not TLS 853", id)
	}
	reason := p.matcher.Trace(domain, ip)
	if ambiguous {
		reason = "shared DNS address; unanimous route or conservative proxy"
	}
	e.logInfo("FLOW.rule", fmt.Sprintf("id=%d domain=%s destination=%s route=%s reason=%s", id, domain, ip, action, reason))
	started := time.Now()
	c, err := e.dialCandidates(ctx, p, network, domain, ip, port, action, id)
	if err != nil {
		return nil, fmt.Errorf("flow=%d domain=%s destination=%s route=%s network=%s elapsed_ms=%d: %w", id, domain, netip.AddrPortFrom(ip, port), action, network, time.Since(started).Milliseconds(), err)
	}
	e.logInfo("FLOW.connected", fmt.Sprintf("id=%d domain=%s destination=%s route=%s elapsed_ms=%d", id, domain, netip.AddrPortFrom(ip, port), action, time.Since(started).Milliseconds()))
	return c, nil
}

func (e *Engine) ipv6Available(action routing.Action) bool {
	if action == routing.ActionDirect {
		return !e.directIPv6Unavailable.Load()
	}
	return action == routing.ActionProxy && e.proxyIPv6
}

// Network changes affect future DNS answers/dials, never expose Proxy as Direct.
func (e *Engine) SetDirectIPv6(available bool) {
	if e.directIPv6Unavailable.Swap(!available) != !available {
		e.logInfo("network.capability", fmt.Sprintf("directIPv6=%t proxyIPv6=%t", available, e.proxyIPv6))
	}
}

func (e *Engine) dialCandidates(ctx context.Context, p *policy, network, domain string, ip netip.Addr, port uint16, action routing.Action, id int64) (net.Conn, error) {
	try := func(candidate netip.Addr) (net.Conn, error) {
		if candidate.Is6() && !e.ipv6Available(action) {
			return nil, fmt.Errorf("IPv6 unavailable on route=%s", action)
		}
		timeout := 3 * time.Second
		if domain == "" {
			timeout = 10 * time.Second
		}
		attempt, cancel := context.WithTimeout(ctx, timeout)
		defer cancel()
		return e.dial(attempt, network, netip.AddrPortFrom(candidate, port), action)
	}
	c, first := try(ip)
	if first == nil {
		return c, nil
	}
	e.logError(fmt.Sprintf("FLOW.attempt id=%d ip=%s route=%s", id, ip, action), first)
	// No replay of application data and no UDP retries: a connected UDP socket
	// does not prove delivery. Unknown IPs cannot be attributed to guessed names.
	if network != "tcp" || domain == "" || ctx.Err() != nil {
		return nil, first
	}
	addresses, err := e.resolveAll(ctx, p, domain, action, false)
	if err != nil {
		return nil, errors.Join(first, err)
	}
	failures := []error{first}
	for _, candidate := range addresses {
		if candidate == ip || p.matcher.Route(domain, candidate) != action {
			continue
		}
		e.logInfo("FLOW.retry", fmt.Sprintf("id=%d ip=%s route=%s same_route=true application_bytes_sent=0", id, candidate, action))
		c, err := try(candidate)
		if err == nil {
			e.logInfo("FLOW.retry.connected", fmt.Sprintf("id=%d ip=%s route=%s", id, candidate, action))
			return c, nil
		}
		e.logError(fmt.Sprintf("FLOW.attempt id=%d ip=%s route=%s", id, candidate, action), err)
		failures = append(failures, err)
		if ctx.Err() != nil {
			break
		}
	}
	return nil, errors.Join(failures...)
}

// UpdateRules atomically changes policy and closes existing flows so newly
// blocked connections cannot survive an update. AWG and the Android TUN stay up.
func (e *Engine) UpdateRules(directory, overrides string) error {
	p, err := loadPolicy(directory, overrides)
	if err != nil {
		return err
	}
	if e.closed.Load() {
		return errors.New("VPN stopped")
	}
	e.mu.Lock()
	if e.closed.Load() {
		e.mu.Unlock()
		return errors.New("VPN stopped")
	}
	previous := e.policy.Swap(p)
	for c := range e.flows {
		c.Close()
	}
	e.mu.Unlock()
	previous.dns.close()
	e.proxyDNSFailures.Store(0)
	e.fakeMu.Lock()
	clear(e.knownIPs)
	e.fakeMu.Unlock()
	go e.warmupDNS(p)
	e.logInfo("routing.policy", fmt.Sprintf("name=%s strategy=%s fakeDNS=%t warnings=%v", p.profile.Name, p.profile.DomainStrategy, bool(p.profile.FakeDNS), p.profile.Warnings()))
	return nil
}
func (e *Engine) Close() {
	if !e.closed.CompareAndSwap(false, true) {
		return
	}
	e.cancel()
	if p := e.policy.Load(); p != nil {
		p.dns.close()
	}
	e.file.Close()
	e.mu.Lock()
	for c := range e.flows {
		c.Close()
	}
	e.mu.Unlock()
	if e.ingress != nil {
		e.ingress.Close()
	}
	if e.link != nil {
		e.link.Close()
	}
	e.networkMu.Lock()
	if e.device != nil {
		e.device.Close()
	}
	e.networkMu.Unlock()
}

// Statistics deliberately exposes counters only, never IpcGet keys or endpoint.
func (e *Engine) Statistics() string {
	stats := map[string]any{"running": !e.closed.Load() && !e.failed.Load(), "rx": int64(0), "tx": int64(0), "handshake": int64(0), "dnsQueries": e.queries.Load(), "dnsErrors": e.dnsErrors.Load(), "proxyDnsFailures": e.proxyDNSFailures.Load(), "dnsCacheHits": e.dnsCacheHits.Load(), "directFlows": e.directFlows.Load(), "proxyFlows": e.proxyFlows.Load(), "blockedFlows": e.blockedFlows.Load(), "directIPv6": !e.directIPv6Unavailable.Load(), "proxyIPv6": e.proxyIPv6, "version": "AWG 3.1.20260814 / Ex WG router 3"}
	if !e.closed.Load() {
		if raw, err := e.device.IpcGet(); err == nil {
			for _, line := range strings.Split(raw, "\n") {
				k, v, _ := strings.Cut(line, "=")
				n, _ := strconv.ParseInt(v, 10, 64)
				switch k {
				case "rx_bytes":
					stats["rx"] = n
				case "tx_bytes":
					stats["tx"] = n
				case "last_handshake_time_sec":
					stats["handshake"] = n
				case "last_handshake_time_nsec":
					stats["handshakeNanos"] = n
				}
			}
		} else {
			e.logError("AWG.IpcGet", err)
			stats["statisticsError"] = errorDetail(err)
		}
	}
	b, _ := json.Marshal(stats)
	return string(b)
}
