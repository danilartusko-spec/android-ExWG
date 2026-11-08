package mobile

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"nexusandroid/internal/routing"
	"strings"
	"time"
)

func publicResourceIP(ip netip.Addr) bool {
	ip = ip.Unmap()
	if !ip.IsValid() || !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return false
	}
	for _, prefix := range []string{"100.64.0.0/10", "198.18.0.0/15", "192.0.0.0/24", "0.0.0.0/8", "240.0.0.0/4", "2001:db8::/32"} {
		if netip.MustParsePrefix(prefix).Contains(ip) {
			return false
		}
	}
	return true
}

// FetchResource is an explicit control-plane download through AWG. It never
// reads through the TUN's synthetic DNS, contacts private hosts, or bypasses
// TLS validation.
func (e *Engine) FetchResource(source string, limit int64, allowHapp bool) ([]byte, error) {
	return e.fetchResource(source, limit, allowHapp, routing.ActionProxy)
}

// FetchResourceDirect downloads outside the TUN through a socket protected by
// Android's VpnService.protect(). Unlike Network.getSocketFactory(), this does
// not bind the socket to a specific Android Network and remains valid when an
// OEM rejects such binding. The system chooses the current underlying network.
func (e *Engine) FetchResourceDirect(source string, limit int64, allowHapp bool) ([]byte, error) {
	return e.fetchResource(source, limit, allowHapp, routing.ActionDirect)
}

func (e *Engine) fetchResource(source string, limit int64, allowHapp bool, action routing.Action) ([]byte, error) {
	if limit <= 0 || limit > 64<<20 {
		return nil, errors.New("resource size limit invalid")
	}
	if e.closed.Load() {
		return nil, errors.New("VPN stopped")
	}
	ctx, cancel := context.WithTimeout(e.ctx, 45*time.Second)
	defer cancel()
	current, err := url.Parse(source)
	if err != nil {
		return nil, err
	}
	p := e.policy.Load()
	route := "proxy"
	if action == routing.ActionDirect {
		route = "protected-direct"
	}
	for hop := 0; hop < 5; hop++ {
		if current.Scheme != "https" || current.Hostname() == "" || current.User != nil {
			return nil, errors.New("resource requires public HTTPS URL without credentials")
		}
		host := current.Hostname()
		ip, err := netip.ParseAddr(host)
		if err != nil {
			ip, err = e.resolve(ctx, p, host, action, false)
		}
		if err != nil {
			return nil, fmt.Errorf("resource host=%s route=%s DNS: %w", host, route, err)
		}
		if !publicResourceIP(ip) {
			return nil, errors.New("resource target is not public")
		}
		port := current.Port()
		if port == "" {
			port = "443"
		}
		addr, err := netip.ParseAddrPort(net.JoinHostPort(ip.String(), port))
		if err != nil {
			return nil, err
		}
		transport := &http.Transport{TLSClientConfig: clientTLSConfig(host), ForceAttemptHTTP2: true, TLSHandshakeTimeout: 8 * time.Second, ResponseHeaderTimeout: 12 * time.Second,
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				return e.dial(ctx, "tcp", addr, action)
			}}
		client := http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
		req, err := http.NewRequestWithContext(ctx, "GET", current.String(), nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("User-Agent", "ExWG-Android/1.0.0")
		response, err := client.Do(req)
		if err != nil {
			transport.CloseIdleConnections()
			return nil, fmt.Errorf("resource host=%s ip=%s route=%s: %w", host, ip, route, err)
		}
		e.logInfo("resource.http", fmt.Sprintf("host=%s route=%s protocol=%s status=%d content_length=%d", host, route, response.Proto, response.StatusCode, response.ContentLength))
		if response.StatusCode >= 300 && response.StatusCode < 400 {
			location := response.Header.Get("Location")
			response.Body.Close()
			transport.CloseIdleConnections()
			if allowHapp && strings.HasPrefix(strings.ToLower(location), "happ://") {
				if int64(len(location)) > limit {
					return nil, errors.New("Happ redirect too large")
				}
				return []byte(location), nil
			}
			if location == "" {
				return nil, errors.New("redirect without Location")
			}
			relative, err := url.Parse(location)
			if err != nil {
				return nil, err
			}
			current = current.ResolveReference(relative)
			continue
		}
		if response.StatusCode != http.StatusOK {
			response.Body.Close()
			transport.CloseIdleConnections()
			return nil, fmt.Errorf("resource HTTP status=%d", response.StatusCode)
		}
		if response.ContentLength > limit {
			response.Body.Close()
			transport.CloseIdleConnections()
			return nil, errors.New("resource too large")
		}
		body, err := io.ReadAll(io.LimitReader(response.Body, limit+1))
		response.Body.Close()
		transport.CloseIdleConnections()
		if err != nil {
			return nil, fmt.Errorf("resource read: %w", err)
		}
		if int64(len(body)) > limit {
			return nil, errors.New("resource exceeds size limit")
		}
		return body, nil
	}
	return nil, errors.New("too many resource redirects")
}
