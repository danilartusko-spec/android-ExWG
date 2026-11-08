package mobile

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"nexusandroid/internal/routing"
)

type dnsEndpoint struct {
	URL       string
	Bootstrap string
}
type dnsCacheEntry struct {
	wire                         []byte
	created, expires, staleUntil time.Time
}
type dnsPending struct {
	done chan struct{}
	wire []byte
	err  error
}
type dnsState struct {
	mu      sync.Mutex
	clients map[string]*http.Client
	cache   map[string]dnsCacheEntry
	pending map[string]*dnsPending
	down    map[string]time.Time
}

func newDNSState() *dnsState {
	return &dnsState{clients: make(map[string]*http.Client), cache: make(map[string]dnsCacheEntry), pending: make(map[string]*dnsPending), down: make(map[string]time.Time)}
}
func (s *dnsState) close() {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, client := range s.clients {
		client.CloseIdleConnections()
	}
}

// resetNetwork drops only transport state tied to the previous physical
// network. Valid DNS answers remain cached, while failed-resolver cooldowns
// are cleared so the new Wi-Fi/mobile path is tried immediately.
func (s *dnsState) resetNetwork() {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, client := range s.clients {
		client.CloseIdleConnections()
	}
	clear(s.down)
}

func parseDNSEndpoint(value string) (dnsEndpoint, error) {
	parts := strings.Fields(value)
	if len(parts) == 0 || len(parts) > 2 {
		return dnsEndpoint{}, errors.New("DNS: ожидается URL и необязательный bootstrap IP")
	}
	endpoint := dnsEndpoint{URL: parts[0]}
	if len(parts) == 2 {
		endpoint.Bootstrap = parts[1]
	}
	if !strings.Contains(endpoint.URL, "://") {
		endpoint.URL = "udp://" + endpoint.URL
	}
	u, err := url.Parse(endpoint.URL)
	if err != nil || u.Hostname() == "" || u.User != nil || u.Fragment != "" {
		return dnsEndpoint{}, errors.New("DNS: неверный адрес сервера")
	}
	switch u.Scheme {
	case "https", "tls", "udp", "tcp":
	default:
		return dnsEndpoint{}, fmt.Errorf("DNS: протокол %q не поддерживается", u.Scheme)
	}
	if u.Scheme == "https" && u.Path == "" {
		u.Path = "/dns-query"
		endpoint.URL = u.String()
	}
	if endpoint.Bootstrap == "" {
		if ip, err := netip.ParseAddr(u.Hostname()); err == nil {
			endpoint.Bootstrap = ip.String()
		} else if known := routing.DefaultBootstrapIP(u.Hostname()); known != "" {
			endpoint.Bootstrap = known
		}
	}
	if _, err := netip.ParseAddr(endpoint.Bootstrap); err != nil {
		return dnsEndpoint{}, fmt.Errorf("DNS %s: нужен bootstrap IP, системный DNS для Proxy не используется", u.Hostname())
	}
	if u.Port() != "" {
		if _, err := netip.ParseAddrPort(net.JoinHostPort(endpoint.Bootstrap, u.Port())); err != nil {
			return dnsEndpoint{}, fmt.Errorf("DNS port: %w", err)
		}
	}
	return endpoint, nil
}
func dnsEndpoints(p *policy, action routing.Action) ([]dnsEndpoint, error) {
	server, bootstrap := dnsServer(p, action)
	key := "dns-" + string(action)
	primary := strings.TrimSpace(server + " " + bootstrap)
	if local := p.overrides.DNS[key]; len(local) > 0 {
		primary = local[len(local)-1]
	}
	values := append([]string{primary}, p.overrides.DNS[key+"-fallback"]...)
	if len(values) > 4 {
		return nil, errors.New("DNS: максимум три резервных сервера на маршрут")
	}
	var result []dnsEndpoint
	for _, value := range values {
		ep, err := parseDNSEndpoint(value)
		if err != nil {
			return nil, err
		}
		result = append(result, ep)
	}
	return result, nil
}

func (e *Engine) dnsHTTPClient(p *policy, endpoint dnsEndpoint, addr netip.AddrPort, action routing.Action) *http.Client {
	key := string(action) + " " + endpoint.URL + " " + addr.String()
	p.dns.mu.Lock()
	defer p.dns.mu.Unlock()
	if client := p.dns.clients[key]; client != nil {
		return client
	}
	u, _ := url.Parse(endpoint.URL)
	transport := &http.Transport{
		TLSClientConfig: clientTLSConfig(u.Hostname()), ForceAttemptHTTP2: true,
		MaxIdleConns: 8, MaxIdleConnsPerHost: 4, MaxConnsPerHost: 8, IdleConnTimeout: 90 * time.Second,
		TLSHandshakeTimeout: 5 * time.Second, ResponseHeaderTimeout: 5 * time.Second,
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) { return e.dial(ctx, "tcp", addr, action) },
	}
	client := &http.Client{Transport: transport, Timeout: 6 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("DNS redirect refused") }}
	p.dns.clients[key] = client
	return client
}

// warmupDNS pre-establishes upstream DNS transports (such as DoH HTTP/2 sessions)
// for Direct and Proxy routes and sends an initial packet through the tunnel so
// AmneziaWG initiates its handshake immediately upon connect or rule reload.
func (e *Engine) warmupDNS(p *policy) {
	if p == nil || e.closed.Load() {
		return
	}
	name, err := dnsmessage.NewName(".")
	if err != nil {
		return
	}
	probe := dnsmessage.Message{
		Header:    dnsmessage.Header{ID: 1, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeNS, Class: dnsmessage.ClassINET}},
	}
	raw, err := probe.Pack()
	if err != nil {
		return
	}
	var wg sync.WaitGroup
	for _, action := range []routing.Action{routing.ActionDirect, routing.ActionProxy} {
		endpoints, err := dnsEndpoints(p, action)
		if err != nil || len(endpoints) == 0 {
			continue
		}
		endpoint := endpoints[0]
		wg.Add(1)
		go func(act routing.Action, ep dnsEndpoint) {
			defer wg.Done()
			warmCtx, cancel := context.WithTimeout(e.ctx, 3*time.Second)
			defer cancel()
			started := time.Now()
			if _, err := e.exchangeDNSAt(warmCtx, p, raw, act, ep); err == nil {
				e.recordProxyDNSResult(act, true)
				e.logInfo("DNS.warmup", fmt.Sprintf("route=%s resolver=%s elapsed_ms=%d", act, ep.URL, time.Since(started).Milliseconds()))
			}
		}(action, endpoint)
	}
	wg.Wait()
}

// Only explicit fallback resolvers are used; egress never changes from Proxy
// to Direct and an encrypted resolver never downgrades to plaintext implicitly.
func (e *Engine) exchangeDNS(ctx context.Context, p *policy, raw []byte, action routing.Action) ([]byte, error) {
	if action == routing.ActionBlock {
		return nil, errors.New("DNS route blocked")
	}
	if len(raw) < 12 {
		return nil, errors.New("DNS query too short")
	}
	key := string(action) + string(raw[2:])
	now := time.Now()
	p.dns.mu.Lock()
	if item, ok := p.dns.cache[key]; ok {
		if now.Before(item.expires) {
			p.dns.mu.Unlock()
			e.dnsCacheHits.Add(1)
			return cachedDNSReply(item, binary.BigEndian.Uint16(raw[:2]), now), nil
		}
		if now.Before(item.staleUntil) {
			if p.dns.pending[key] == nil {
				queryCopy := append([]byte(nil), raw...)
				go e.revalidateDNS(p, queryCopy, action)
			}
			p.dns.mu.Unlock()
			e.dnsCacheHits.Add(1)
			e.logInfo("DNS.cache", fmt.Sprintf("route=%s hit=stale-revalidate", action))
			return cachedDNSReply(item, binary.BigEndian.Uint16(raw[:2]), now), nil
		}
	}
	if pending := p.dns.pending[key]; pending != nil {
		p.dns.mu.Unlock()
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-pending.done:
		}
		if pending.err != nil {
			return nil, pending.err
		}
		reply := append([]byte(nil), pending.wire...)
		binary.BigEndian.PutUint16(reply, binary.BigEndian.Uint16(raw))
		return reply, nil
	}
	pending := &dnsPending{done: make(chan struct{})}
	p.dns.pending[key] = pending
	p.dns.mu.Unlock()
	reply, err := e.exchangeDNSFallbacks(ctx, p, raw, action)
	p.dns.mu.Lock()
	pending.wire, pending.err = reply, err
	if err == nil {
		if ttl := cacheTTL(reply); ttl > 0 {
			if len(p.dns.cache) >= 512 {
				for k, v := range p.dns.cache {
					if now.After(v.expires) {
						delete(p.dns.cache, k)
					}
				}
				if len(p.dns.cache) >= 512 {
					for k := range p.dns.cache {
						delete(p.dns.cache, k)
						break
					}
				}
			}
			createdAt := time.Now()
			p.dns.cache[key] = dnsCacheEntry{
				wire:       append([]byte(nil), reply...),
				created:    createdAt,
				expires:    createdAt.Add(ttl),
				staleUntil: createdAt.Add(ttl + 5*time.Minute),
			}
		}
	}
	delete(p.dns.pending, key)
	close(pending.done)
	p.dns.mu.Unlock()
	return reply, err
}

func (e *Engine) revalidateDNS(p *policy, raw []byte, action routing.Action) {
	if p == nil || e.closed.Load() || len(raw) < 12 || action == routing.ActionBlock {
		return
	}
	key := string(action) + string(raw[2:])
	p.dns.mu.Lock()
	if p.dns.pending[key] != nil {
		p.dns.mu.Unlock()
		return
	}
	pending := &dnsPending{done: make(chan struct{})}
	p.dns.pending[key] = pending
	p.dns.mu.Unlock()

	ctx, cancel := context.WithTimeout(e.ctx, 5*time.Second)
	defer cancel()
	reply, err := e.exchangeDNSFallbacks(ctx, p, raw, action)
	p.dns.mu.Lock()
	pending.wire, pending.err = reply, err
	if err == nil {
		if ttl := cacheTTL(reply); ttl > 0 {
			createdAt := time.Now()
			p.dns.cache[key] = dnsCacheEntry{
				wire:       append([]byte(nil), reply...),
				created:    createdAt,
				expires:    createdAt.Add(ttl),
				staleUntil: createdAt.Add(ttl + 5*time.Minute),
			}
		}
	}
	delete(p.dns.pending, key)
	close(pending.done)
	p.dns.mu.Unlock()

	if err == nil {
		var q dnsmessage.Message
		if q.Unpack(raw) == nil && len(q.Questions) == 1 {
			domain := strings.TrimSuffix(strings.ToLower(q.Questions[0].Name.String()), ".")
			_ = e.rememberDNS(p, domain, reply)
		}
	}
}
func (e *Engine) exchangeDNSFallbacks(ctx context.Context, p *policy, raw []byte, action routing.Action) ([]byte, error) {
	endpoints, err := dnsEndpoints(p, action)
	if err != nil {
		return nil, err
	}
	var failures []error
	var q dnsmessage.Message
	_ = q.Unpack(raw)
	domain := "?"
	if len(q.Questions) == 1 {
		domain = q.Questions[0].Name.String()
	}
	for i, endpoint := range endpoints {
		key := string(action) + " " + endpoint.URL
		p.dns.mu.Lock()
		down := time.Now().Before(p.dns.down[key])
		p.dns.mu.Unlock()
		if down && i < len(endpoints)-1 {
			e.logInfo("DNS.cooldown", key)
			continue
		}
		attempt, cancel := context.WithTimeout(ctx, 4*time.Second)
		started := time.Now()
		reply, err := e.exchangeDNSAt(attempt, p, raw, action, endpoint)
		cancel()
		if err == nil {
			var message dnsmessage.Message
			_ = message.Unpack(reply)
			// NXDOMAIN and NODATA are authoritative results, not network failure.
			if message.RCode == dnsmessage.RCodeServerFailure || message.RCode == dnsmessage.RCodeRefused {
				err = fmt.Errorf("DNS rcode=%d", message.RCode)
			}
		}
		if err == nil {
			p.dns.mu.Lock()
			delete(p.dns.down, key)
			p.dns.mu.Unlock()
			e.recordProxyDNSResult(action, true)
			e.logInfo("DNS.answer", fmt.Sprintf("domain=%s route=%s resolver=%s fallback=%t elapsed_ms=%d", domain, action, endpoint.URL, i > 0, time.Since(started).Milliseconds()))
			return reply, nil
		}
		wrapped := fmt.Errorf("domain=%s route=%s resolver=%s bootstrap=%s attempt=%d: %w", domain, action, endpoint.URL, endpoint.Bootstrap, i+1, err)
		e.logError("DNS.upstream", wrapped)
		failures = append(failures, wrapped)
		p.dns.mu.Lock()
		p.dns.down[key] = time.Now().Add(30 * time.Second)
		p.dns.mu.Unlock()
		if ctx.Err() != nil {
			break
		}
	}
	e.recordProxyDNSResult(action, false)
	return nil, errors.Join(failures...)
}

func (e *Engine) recordProxyDNSResult(action routing.Action, succeeded bool) {
	if action != routing.ActionProxy {
		return
	}
	if succeeded {
		e.proxyDNSFailures.Store(0)
	} else {
		e.proxyDNSFailures.Add(1)
	}
}
func cacheTTL(wire []byte) time.Duration {
	var msg dnsmessage.Message
	if msg.Unpack(wire) != nil || msg.Truncated {
		return 0
	}
	if msg.RCode != dnsmessage.RCodeSuccess && msg.RCode != dnsmessage.RCodeNameError {
		return 0
	}
	ttl := uint32(300)
	found := false
	for _, rr := range append(append([]dnsmessage.Resource{}, msg.Answers...), msg.Authorities...) {
		if rr.Header.TTL < ttl {
			ttl = rr.Header.TTL
		}
		found = true
		if soa, ok := rr.Body.(*dnsmessage.SOAResource); ok && soa.MinTTL < ttl {
			ttl = soa.MinTTL
		}
	}
	if !found || len(wire) > 16384 {
		return 0
	}
	return time.Duration(ttl) * time.Second
}
func cachedDNSReply(item dnsCacheEntry, id uint16, now time.Time) []byte {
	var msg dnsmessage.Message
	_ = msg.Unpack(item.wire)
	msg.ID = id
	age := uint32(now.Sub(item.created) / time.Second)
	for _, records := range [][]dnsmessage.Resource{msg.Answers, msg.Authorities, msg.Additionals} {
		for i := range records {
			if records[i].Header.Type == dnsmessage.TypeOPT {
				continue
			}
			if records[i].Header.TTL > age {
				records[i].Header.TTL -= age
			} else {
				records[i].Header.TTL = 15
			}
		}
	}
	b, _ := msg.Pack()
	return b
}

func (e *Engine) queryDNS(ctx context.Context, p *policy, raw []byte, action routing.Action) ([]byte, error) {
	var q dnsmessage.Message
	if q.Unpack(raw) != nil || len(q.Questions) != 1 {
		return nil, errors.New("invalid DNS query")
	}
	question := q.Questions[0]
	domain := strings.TrimSuffix(strings.ToLower(question.Name.String()), ".")
	if host := p.profile.DNSHosts[domain]; host != "" && (question.Type == dnsmessage.TypeA || question.Type == dnsmessage.TypeAAAA) {
		ip, err := netip.ParseAddr(host)
		if err != nil {
			return nil, err
		}
		e.logInfo("DNS.hosts", fmt.Sprintf("domain=%s address=%s route=%s", domain, ip, action))
		msg := dnsmessage.Message{Header: dnsmessage.Header{ID: q.ID, Response: true, RecursionDesired: q.RecursionDesired, RecursionAvailable: true}, Questions: q.Questions}
		header := dnsmessage.ResourceHeader{Name: question.Name, Type: question.Type, Class: question.Class, TTL: 60}
		if ip.Is4() && question.Type == dnsmessage.TypeA {
			msg.Answers = []dnsmessage.Resource{{Header: header, Body: &dnsmessage.AResource{A: ip.As4()}}}
		}
		if ip.Is6() && question.Type == dnsmessage.TypeAAAA {
			msg.Answers = []dnsmessage.Resource{{Header: header, Body: &dnsmessage.AAAAResource{AAAA: ip.As16()}}}
		}
		return msg.Pack()
	}
	return e.exchangeDNS(ctx, p, raw, action)
}
