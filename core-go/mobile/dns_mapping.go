package mobile

import (
	"fmt"
	"golang.org/x/net/dns/dnsmessage"
	"net/netip"
	"nexusandroid/internal/routing"
	"time"
)

// Real DNS mode retains the genuine answers and remembers names only for their
// TTL. A shared CDN address is not evidence of a unique domain: ambiguity must
// never silently select Direct. FakeDNS remains available when explicitly set.
func (e *Engine) rememberDNS(p *policy, domain string, wire []byte) []byte {
	var msg dnsmessage.Message
	if msg.Unpack(wire) != nil || msg.RCode != dnsmessage.RCodeSuccess {
		return wire
	}
	now := time.Now()
	e.fakeMu.Lock()
	defer e.fakeMu.Unlock()
	if e.knownIPs == nil {
		e.knownIPs = make(map[netip.Addr]map[string]time.Time)
	}
	if len(e.knownIPs) >= 4096 {
		for ip, names := range e.knownIPs {
			for name, until := range names {
				if !now.Before(until) {
					delete(names, name)
				}
			}
			if len(names) == 0 {
				delete(e.knownIPs, ip)
			}
		}
	}
	answers := make([]dnsmessage.Resource, 0, len(msg.Answers))
	for _, rr := range msg.Answers {
		if body, ok := rr.Body.(*dnsmessage.UnknownResource); ok && (rr.Header.Type == 64 || rr.Header.Type == 65) {
			data, hints, changed := filterServiceBinding(body.Data, func(ip netip.Addr) bool { return ip.Is4() || e.ipv6Available(p.matcher.Route(domain, ip)) })
			if changed {
				msg.AuthenticData = false
				e.logInfo("DNS.serviceBinding", fmt.Sprintf("domain=%s unavailable address hints filtered", domain))
			}
			if data == nil {
				continue
			}
			rr.Body = &dnsmessage.UnknownResource{Type: body.Type, Data: data}
			for _, ip := range hints {
				e.rememberAddressLocked(ip, domain, rr.Header.TTL, now)
			}
		}
		var ip netip.Addr
		switch body := rr.Body.(type) {
		case *dnsmessage.AResource:
			ip = netip.AddrFrom4(body.A)
		case *dnsmessage.AAAAResource:
			ip = netip.AddrFrom16(body.AAAA)
		}
		if ip.IsValid() {
			if ip.Is6() && !e.ipv6Available(p.matcher.Route(domain, ip)) {
				e.logInfo("DNS.AAAA", fmt.Sprintf("domain=%s ip=%s suppressed: route=%s IPv6 unavailable; IPv4 remains eligible", domain, ip, p.matcher.Route(domain, ip)))
				msg.AuthenticData = false
				continue
			}
			e.rememberAddressLocked(ip, domain, rr.Header.TTL, now)
		}
		answers = append(answers, rr)
	}
	msg.Answers = answers
	result, err := msg.Pack()
	if err != nil {
		return wire
	}
	return result
}

func (e *Engine) rememberAddressLocked(ip netip.Addr, domain string, ttl uint32, now time.Time) {
	if ttl == 0 {
		return
	}
	if ttl > 300 {
		ttl = 300
	}
	names := e.knownIPs[ip]
	if names == nil && len(e.knownIPs) < 4096 {
		names = make(map[string]time.Time)
		e.knownIPs[ip] = names
	}
	if names != nil {
		if len(names) < 8 {
			names[domain] = now.Add(time.Duration(ttl) * time.Second)
		} else {
			names[""] = now.Add(time.Duration(ttl) * time.Second)
		}
	}
}
func (e *Engine) knownDomain(ip netip.Addr) (string, bool) {
	e.fakeMu.Lock()
	defer e.fakeMu.Unlock()
	names := e.knownIPs[ip]
	now := time.Now()
	domain := ""
	count := 0
	for name, until := range names {
		if !now.Before(until) {
			delete(names, name)
			continue
		}
		count++
		domain = name
	}
	if count == 0 {
		delete(e.knownIPs, ip)
		return "", false
	}
	if count != 1 || domain == "" {
		return "", true
	}
	return domain, false
}

// CDN sharing does not require Proxy when every current name selects the same
// route. Mixed/overflow evidence remains Proxy; never guess one cached name.
func (e *Engine) sharedDNSRoute(p *policy, ip netip.Addr) routing.Action {
	e.fakeMu.Lock()
	defer e.fakeMu.Unlock()
	var selected routing.Action
	now := time.Now()
	for name, until := range e.knownIPs[ip] {
		if !now.Before(until) {
			continue
		}
		if name == "" {
			return routing.ActionProxy
		}
		action := p.matcher.Route(name, ip)
		if selected != "" && selected != action {
			return routing.ActionProxy
		}
		selected = action
	}
	if selected == "" {
		return routing.ActionProxy
	}
	return selected
}
