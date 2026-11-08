package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"golang.org/x/net/dns/dnsmessage"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"nexusandroid/internal/routing"
	"regexp"
	"strings"
	"time"
)

// ProbeDomain checks DNS and HTTPS through precisely the live routing policy.
// It returns no destination IP, endpoint, keys, response body or user traffic.
func (e *Engine) ProbeDomain(domain string) string {
	result := map[string]any{"ok": false, "action": "unknown"}
	started := time.Now()
	domain = strings.ToLower(strings.TrimSpace(domain))
	if len(domain) > 253 || !regexp.MustCompile(`^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,63}$`).MatchString(domain) || strings.Contains(domain, "..") {
		return `{"ok":false,"error":"Enter a public domain name, without URL path or credentials"}`
	}
	result["domain"] = domain
	result["action"] = string(e.policy.Load().matcher.Domain(domain))
	fake, err := e.fakeAddress(domain, false)
	if err == nil && !e.closed.Load() {
		transport := &http.Transport{ForceAttemptHTTP2: true, TLSClientConfig: clientTLSConfig(domain), DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) { return e.dialTarget(ctx, "tcp", fake, 443) }}
		defer transport.CloseIdleConnections()
		client := http.Client{Transport: transport, Timeout: 15 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
		req, _ := http.NewRequestWithContext(e.ctx, "HEAD", "https://"+domain+"/", nil)
		resp, requestErr := client.Do(req)
		if requestErr == nil {
			result["ok"] = true
			result["httpStatus"] = resp.StatusCode
			result["httpProtocol"] = resp.Proto
			result["dnsOk"] = true
			resp.Body.Close()
		} else {
			err = requestErr
		}
	} else if err == nil {
		err = errors.New("VPN stopped")
	}
	if err != nil {
		result["error"] = errorDetail(err)
		result["stage"] = "TCP/TLS/HTTPS"
		if strings.Contains(err.Error(), "stage=DNS") {
			result["stage"] = "DNS"
			result["dnsOk"] = false
		}
		e.logError("probe."+domain, err)
	}
	result["elapsedMs"] = time.Since(started).Milliseconds()
	b, _ := json.Marshal(result)
	return string(b)
}

func (e *Engine) CheckRoute(target string) string {
	p := e.policy.Load()
	if p == nil || e.closed.Load() {
		return `{"error":"VPN не подключен"}`
	}
	cleaned := strings.TrimSpace(target)
	if strings.Contains(cleaned, "://") {
		if u, err := url.Parse(cleaned); err == nil && u.Hostname() != "" {
			cleaned = u.Hostname()
		}
	}
	cleaned = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(cleaned)), ".")
	if cleaned == "" {
		return `{"error":"укажите домен или IP-адрес"}`
	}
	if ip, err := netip.ParseAddr(cleaned); err == nil {
		action := p.matcher.IP(ip)
		rule := p.matcher.Trace("", ip)
		path := "direct"
		if action == routing.ActionProxy {
			path = "exwg"
		} else if action == routing.ActionBlock {
			path = "blocked"
		}
		b, _ := json.Marshal(map[string]any{
			"domain":    ip.String(),
			"action":    string(action),
			"rule":      rule,
			"addresses": []string{ip.String()},
			"paths":     []string{path},
		})
		return string(b)
	}
	domainAction := p.matcher.Domain(cleaned)
	var addresses []string
	var paths []string
	var firstAddr netip.Addr
	var dnsErr string
	if domainAction != routing.ActionBlock {
		ctx, cancel := context.WithTimeout(e.ctx, 6*time.Second)
		resolved, err := e.resolveAll(ctx, p, cleaned, domainAction, false)
		cancel()
		if err != nil {
			dnsErr = errorDetail(err)
		} else {
			for i, addr := range resolved {
				if i == 0 {
					firstAddr = addr
				}
				addresses = append(addresses, addr.String())
				addrAction := p.matcher.Route(cleaned, addr)
				path := "direct"
				if addrAction == routing.ActionProxy {
					path = "exwg"
				} else if addrAction == routing.ActionBlock {
					path = "blocked"
				}
				paths = append(paths, path)
			}
		}
	}
	action := p.matcher.Route(cleaned, firstAddr)
	rule := p.matcher.Trace(cleaned, firstAddr)
	result := map[string]any{
		"domain":    cleaned,
		"action":    string(action),
		"rule":      rule,
		"addresses": addresses,
		"paths":     paths,
	}
	if dnsErr != "" {
		result["dnsError"] = dnsErr
	}
	b, _ := json.Marshal(result)
	return string(b)
}

func (e *Engine) ProbeDNS() string {
	results := make(map[string]any)
	for _, action := range []routing.Action{routing.ActionDirect, routing.ActionProxy} {
		p := e.policy.Load()
		endpoints, _ := dnsEndpoints(p, action)
		item := map[string]any{"ok": false, "route": action, "resolvers": endpoints}
		name, _ := dnsmessage.NewName("example.com.")
		q := dnsmessage.Message{Header: dnsmessage.Header{ID: 902, RecursionDesired: true}, Questions: []dnsmessage.Question{{Name: name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET}}}
		wire, _ := q.Pack()
		ctx, cancel := context.WithTimeout(e.ctx, 10*time.Second)
		started := time.Now()
		reply, err := e.exchangeDNSFallbacks(ctx, p, wire, action)
		cancel()
		if err != nil {
			item["error"] = errorDetail(err)
		} else {
			var msg dnsmessage.Message
			msg.Unpack(reply)
			item["rcode"] = int(msg.RCode)
			item["answers"] = len(msg.Answers)
			item["ok"] = msg.RCode == dnsmessage.RCodeSuccess && len(msg.Answers) > 0
		}
		item["elapsedMs"] = time.Since(started).Milliseconds()
		results[string(action)] = item
	}
	b, _ := json.Marshal(results)
	return string(b)
}

