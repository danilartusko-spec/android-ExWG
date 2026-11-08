package mobile

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"golang.org/x/net/dns/dnsmessage"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"nexusandroid/internal/routing"
	"strings"
	"sync"
	"time"
)

func (e *Engine) fakeAddress(domain string, v6 bool) (netip.Addr, error) {
	e.fakeMu.Lock()
	defer e.fakeMu.Unlock()
	key := domain
	if v6 {
		key += "/6"
	}
	if ip, ok := e.fakeByName[key]; ok {
		return ip, nil
	}
	if len(e.fakeByName) >= 65000 {
		return netip.Addr{}, errors.New("DNS map capacity reached")
	}
	n := uint32(len(e.fakeByName) + 1)
	ip := netip.AddrFrom4([4]byte{198, 18 + byte(n>>16), byte(n >> 8), byte(n)})
	if v6 {
		b := [16]byte{0xfd, 0x6e, 0x65, 0x78, 0x75, 0x73}
		binary.BigEndian.PutUint32(b[12:], n)
		ip = netip.AddrFrom16(b)
	}
	e.fakeByName[key] = ip
	e.nameByFake[ip] = domain
	return ip, nil
}

func (e *Engine) serveDNS(c net.Conn, tcp bool) {
	for {
		c.SetDeadline(time.Now().Add(20 * time.Second))
		var raw []byte
		if tcp {
			var h [2]byte
			if _, err := io.ReadFull(c, h[:]); err != nil {
				return
			}
			raw = make([]byte, int(binary.BigEndian.Uint16(h[:])))
			if _, err := io.ReadFull(c, raw); err != nil {
				return
			}
		} else {
			raw = make([]byte, 65535)
			n, err := c.Read(raw)
			if err != nil {
				return
			}
			raw = raw[:n]
		}
		response := e.answerDNS(raw)
		if response == nil {
			continue
		}
		if tcp {
			h := []byte{byte(len(response) >> 8), byte(len(response))}
			response = append(h, response...)
		}
		if _, err := c.Write(response); err != nil {
			return
		}
	}
}

func (e *Engine) answerDNS(raw []byte) []byte {
	var query dnsmessage.Message
	if query.Unpack(raw) != nil || query.Header.Response || len(query.Questions) != 1 {
		return nil
	}
	e.queries.Add(1)
	q := query.Questions[0]
	domain := strings.TrimSuffix(strings.ToLower(q.Name.String()), ".")
	response := dnsmessage.Message{Header: dnsmessage.Header{ID: query.ID, Response: true, RecursionDesired: query.RecursionDesired, RecursionAvailable: true}, Questions: query.Questions}
	p := e.policy.Load()
	action := p.matcher.Domain(domain)
	if q.Class != dnsmessage.ClassINET {
		response.RCode = dnsmessage.RCodeRefused
	} else if action == routing.ActionBlock {
		e.blockedFlows.Add(1)
		e.logInfo("DNS.block", fmt.Sprintf("domain=%s type=%d reason=%s", domain, q.Type, p.matcher.Trace(domain, netip.Addr{})))
		response.RCode = dnsmessage.RCodeNameError
	} else if (q.Type == dnsmessage.TypeA || q.Type == dnsmessage.TypeAAAA) && bool(p.profile.FakeDNS) {
		ip, err := e.fakeAddress(domain, q.Type == dnsmessage.TypeAAAA)
		if err != nil {
			response.RCode = dnsmessage.RCodeServerFailure
		} else {
			header := dnsmessage.ResourceHeader{Name: q.Name, Type: q.Type, Class: q.Class, TTL: 30}
			if ip.Is4() {
				response.Answers = []dnsmessage.Resource{{Header: header, Body: &dnsmessage.AResource{A: ip.As4()}}}
			} else {
				response.Answers = []dnsmessage.Resource{{Header: header, Body: &dnsmessage.AAAAResource{AAAA: ip.As16()}}}
			}
		}
	} else if bool(p.profile.FakeDNS) && (q.Type == dnsmessage.Type(64) || q.Type == dnsmessage.Type(65)) {
		// FakeDNS must not expose real HTTPS/SVCB address hints.
	} else if q.Type == dnsmessage.TypeAAAA && !e.ipv6Available(action) && !e.ipv6Available(routing.ActionDirect) && !e.ipv6Available(routing.ActionProxy) {
		// Short-circuit TypeAAAA locally with NODATA when neither Direct nor Proxy
		// has a usable IPv6 path, avoiding an unnecessary upstream DoH round-trip.
	} else {
		ctx, cancel := context.WithTimeout(e.ctx, 10*time.Second)
		defer cancel()
		if reply, err := e.queryDNS(ctx, p, raw, action); err == nil {
			return e.rememberDNS(p, domain, reply)
		} else {
			e.logError(fmt.Sprintf("DNS.exchange domain=%s type=%d route=%s", domain, q.Type, action), err)
		}
		e.dnsErrors.Add(1)
		response.RCode = dnsmessage.RCodeServerFailure
	}
	result, _ := response.Pack()
	return result
}

func (e *Engine) resolve(ctx context.Context, p *policy, domain string, action routing.Action, v6 bool) (netip.Addr, error) {
	addresses, err := e.resolveAll(ctx, p, domain, action, v6)
	if err != nil {
		return netip.Addr{}, err
	}
	return addresses[0], nil
}

func (e *Engine) resolveAll(ctx context.Context, p *policy, domain string, action routing.Action, v6 bool) ([]netip.Addr, error) {
	if host := p.profile.DNSHosts[domain]; host != "" {
		ip, err := netip.ParseAddr(host)
		if err != nil {
			return nil, err
		}
		routeAction := p.matcher.Route(domain, ip)
		if ip.Is6() && !e.ipv6Available(routeAction) {
			return nil, fmt.Errorf("DNS static host %s (%s) IPv6 unavailable on route=%s", domain, ip, routeAction)
		}
		e.logInfo("DNS.hosts", fmt.Sprintf("domain=%s address=%s route=%s", domain, ip, routeAction))
		return []netip.Addr{ip}, nil
	}
	name, err := dnsmessage.NewName(domain + ".")
	if err != nil {
		return nil, fmt.Errorf("invalid DNS name: %w", err)
	}
	// Prefer IPv4 to support common IPv4-only AWG servers, while permitting IPv6-only hosts.
	types := []dnsmessage.Type{dnsmessage.TypeA}
	if e.ipv6Available(action) {
		if v6 {
			types = []dnsmessage.Type{dnsmessage.TypeAAAA, dnsmessage.TypeA}
		} else {
			types = append(types, dnsmessage.TypeAAAA)
		}
	}
	type queryResult struct {
		reply []byte
		err   error
	}
	results := make([]queryResult, len(types))
	var wg sync.WaitGroup
	for idx, typ := range types {
		wg.Add(1)
		go func(i int, qtype dnsmessage.Type) {
			defer wg.Done()
			var seed [2]byte
			rand.Read(seed[:])
			id := binary.BigEndian.Uint16(seed[:])
			query := dnsmessage.Message{Header: dnsmessage.Header{ID: id, RecursionDesired: true}, Questions: []dnsmessage.Question{{Name: name, Type: qtype, Class: dnsmessage.ClassINET}}}
			raw, _ := query.Pack()
			queryContext, cancel := context.WithTimeout(ctx, 3*time.Second)
			reply, err := e.queryDNS(queryContext, p, raw, action)
			cancel()
			results[i] = queryResult{reply: reply, err: err}
		}(idx, typ)
	}
	wg.Wait()

	var addresses []netip.Addr
	var failures []error
	seen := make(map[netip.Addr]bool)
	for _, res := range results {
		if res.err != nil {
			failures = append(failures, res.err)
			continue
		}
		var msg dnsmessage.Message
		if msg.Unpack(res.reply) != nil || msg.RCode != dnsmessage.RCodeSuccess {
			continue
		}
		for _, r := range msg.Answers {
			var ip netip.Addr
			switch b := r.Body.(type) {
			case *dnsmessage.AResource:
				ip = netip.AddrFrom4(b.A)
			case *dnsmessage.AAAAResource:
				ip = netip.AddrFrom16(b.AAAA)
			}
			if ip.IsValid() && !seen[ip] && len(addresses) < 8 {
				seen[ip] = true
				addresses = append(addresses, ip)
			}
		}
	}
	if len(addresses) > 0 {
		return addresses, nil
	}
	return nil, errors.Join(append(failures, errors.New("DNS returned no usable address"))...)
}

func dnsServer(p *policy, action routing.Action) (string, string) {
	server, bootstrap := p.profile.RemoteDNS, p.profile.RemoteDNSIP
	kind, domain := p.profile.RemoteDNSType, p.profile.RemoteDNSDomain
	if action == routing.ActionDirect {
		server, bootstrap = p.profile.DomesticDNS, p.profile.DomesticDNSIP
		kind, domain = p.profile.DomesticDNSType, p.profile.DomesticDNSDomain
	}
	if server == "" {
		server = bootstrap
	}
	switch strings.ToLower(kind) {
	case "doh":
		if domain != "" {
			server = domain
		}
		if !strings.Contains(server, "://") {
			server = "https://" + server + "/dns-query"
		}
	case "dot":
		if domain != "" {
			server = domain
		}
		if !strings.Contains(server, "://") {
			server = "tls://" + server
		}
	case "dou", "udp":
		if !strings.Contains(server, "://") && server != "" {
			server = "udp://" + server
		}
	case "tcp":
		if !strings.Contains(server, "://") && server != "" {
			server = "tcp://" + server
		}
	}
	if server == "" {
		server = bootstrap
	}
	if server == "" {
		server, bootstrap = "https://common.dot.dns.yandex.net/dns-query", "77.88.8.8"
	}
	return server, bootstrap
}
func (e *Engine) exchangeDNSAt(ctx context.Context, p *policy, raw []byte, action routing.Action, endpoint dnsEndpoint) ([]byte, error) {
	server, bootstrap := endpoint.URL, endpoint.Bootstrap
	if !strings.Contains(server, "://") {
		server = "udp://" + server
	}
	u, err := url.Parse(server)
	if err != nil || u.Hostname() == "" {
		return nil, errors.New("DNS endpoint invalid")
	}
	ip, err := netip.ParseAddr(u.Hostname())
	if err != nil {
		ip, err = netip.ParseAddr(bootstrap)
	}
	if err != nil {
		return nil, errors.New("DNS endpoint requires bootstrap IP")
	}
	port := u.Port()
	if port == "" {
		switch u.Scheme {
		case "https":
			port = "443"
		case "tls":
			port = "853"
		default:
			port = "53"
		}
	}
	addr, err := netip.ParseAddrPort(net.JoinHostPort(ip.String(), port))
	if err != nil {
		return nil, fmt.Errorf("DNS port invalid: %w", err)
	}
	var reply []byte
	if u.Scheme == "https" {
		client := e.dnsHTTPClient(p, endpoint, addr, action)
		req, err := http.NewRequestWithContext(ctx, "POST", u.String(), bytes.NewReader(raw))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Content-Type", "application/dns-message")
		req.Header.Set("Accept", "application/dns-message")
		resp, err := client.Do(req)
		if err != nil {
			return nil, fmt.Errorf("encrypted DNS request failed: %w", err)
		}
		defer resp.Body.Close()
		e.logInfo("DNS.transport", fmt.Sprintf("resolver=%s route=%s protocol=%s status=%d", u.Redacted(), action, resp.Proto, resp.StatusCode))
		if resp.StatusCode != 200 {
			return nil, fmt.Errorf("encrypted DNS HTTP status=%d %s", resp.StatusCode, resp.Status)
		}
		reply, err = io.ReadAll(io.LimitReader(resp.Body, 65536))
		if err != nil {
			return nil, fmt.Errorf("DNS response read: %w", err)
		}
		if len(reply) > 65535 {
			return nil, errors.New("DNS response too large")
		}
	} else {
		network := "udp"
		if u.Scheme == "tcp" || u.Scheme == "tls" {
			network = "tcp"
		} else if u.Scheme != "udp" {
			return nil, errors.New("DNS protocol unsupported")
		}
		c, err := e.dial(ctx, network, addr, action)
		if err != nil {
			return nil, fmt.Errorf("DNS connection failed: %w", err)
		}
		defer c.Close()
		deadline := time.Now().Add(5 * time.Second)
		if limit, ok := ctx.Deadline(); ok && limit.Before(deadline) {
			deadline = limit
		}
		c.SetDeadline(deadline)
		stopCancel := context.AfterFunc(ctx, func() { c.Close() })
		defer stopCancel()
		if u.Scheme == "tls" {
			secure := tls.Client(c, clientTLSConfig(u.Hostname()))
			if err := secure.HandshakeContext(ctx); err != nil {
				return nil, fmt.Errorf("DNS TLS validation failed: %w", err)
			}
			c = secure
		}
		if network == "tcp" {
			if _, err = c.Write(append([]byte{byte(len(raw) >> 8), byte(len(raw))}, raw...)); err != nil {
				return nil, err
			}
			var h [2]byte
			if _, err = io.ReadFull(c, h[:]); err != nil {
				return nil, err
			}
			reply = make([]byte, int(binary.BigEndian.Uint16(h[:])))
			_, err = io.ReadFull(c, reply)
			if err != nil {
				return nil, err
			}
		} else {
			if _, err = c.Write(raw); err != nil {
				return nil, err
			}
			reply = make([]byte, 65535)
			n, err := c.Read(reply)
			if err != nil {
				return nil, err
			}
			reply = reply[:n]
		}
	}
	var query, response dnsmessage.Message
	if query.Unpack(raw) != nil || response.Unpack(reply) != nil || !response.Response || query.ID != response.ID || len(response.Questions) != 1 || len(query.Questions) != 1 || response.Questions[0] != query.Questions[0] {
		return nil, errors.New("DNS response validation failed")
	}
	if response.Truncated && u.Scheme == "udp" {
		u.Scheme = "tcp"
		return e.exchangeDNSAt(ctx, p, raw, action, dnsEndpoint{URL: u.String(), Bootstrap: bootstrap})
	}
	if response.Truncated {
		return nil, errors.New("DNS truncated response over reliable transport")
	}
	return reply, nil
}
