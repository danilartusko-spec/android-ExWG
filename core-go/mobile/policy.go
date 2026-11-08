// Package mobile exposes a small, secret-free API to the native Android service.
package mobile

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"net/url"
	"nexusandroid/internal/routing"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type policy struct {
	profile   *routing.Profile
	matcher   *routing.Matcher
	dns       *dnsState
	overrides routing.Overrides
}

type fileStamp struct {
	modTime time.Time
	size    int64
}

type cachedPolicyEntry struct {
	directory   string
	overrides   string
	profileStat fileStamp
	geositeStat fileStamp
	geoipStat   fileStamp
	profile     *routing.Profile
	matcher     *routing.Matcher
	parsed      routing.Overrides
}

var (
	policyCacheMu sync.Mutex
	policyCache   cachedPolicyEntry
)

func statStamp(path string) fileStamp {
	if path == "" {
		return fileStamp{}
	}
	info, err := os.Stat(path)
	if err != nil {
		return fileStamp{size: -1}
	}
	return fileStamp{modTime: info.ModTime(), size: info.Size()}
}

func loadPolicy(directory, overrides string) (*policy, error) {
	var profileStat, geositeStat, geoipStat fileStamp
	if directory != "" {
		profileStat = statStamp(filepath.Join(directory, "profile.json"))
		geositeStat = statStamp(filepath.Join(directory, "geosite.dat"))
		geoipStat = statStamp(filepath.Join(directory, "geoip.dat"))
	}
	policyCacheMu.Lock()
	if policyCache.profile != nil &&
		policyCache.matcher != nil &&
		policyCache.directory == directory &&
		policyCache.overrides == overrides &&
		policyCache.profileStat == profileStat &&
		policyCache.geositeStat == geositeStat &&
		policyCache.geoipStat == geoipStat {
		cachedProfile := policyCache.profile
		cachedMatcher := policyCache.matcher
		cachedOverrides := policyCache.parsed
		policyCacheMu.Unlock()
		return &policy{
			profile:   cachedProfile,
			matcher:   cachedMatcher,
			dns:       newDNSState(),
			overrides: cachedOverrides,
		}, nil
	}
	policyCacheMu.Unlock()

	var p *routing.Profile
	var sites, ips []byte
	var err error
	if directory == "" {
		p = &routing.Profile{Name: "Ex WG: встроенные правила", GlobalProxy: true, DomainStrategy: "IPIfNonMatch",
			DirectSites:     []string{"domain:ru", "domain:xn--p1ai"},
			DomesticDNSType: "DoH", DomesticDNSDomain: "https://common.dot.dns.yandex.net/dns-query", DomesticDNSIP: "77.88.8.8",
			RemoteDNSType: "DoH", RemoteDNSDomain: "https://common.dot.dns.yandex.net/dns-query", RemoteDNSIP: "77.88.8.8"}
	} else {
		data, e := readLimited(filepath.Join(directory, "profile.json"), 2<<20)
		if e != nil {
			return nil, e
		}
		p, err = routing.ParseProfile(string(data))
		if err != nil {
			return nil, fmt.Errorf("routing profile: %w", err)
		}
		if p.GeoSiteURL != "" {
			sites, err = readLimited(filepath.Join(directory, "geosite.dat"), 64<<20)
			if err != nil {
				return nil, err
			}
		}
		if p.GeoIPURL != "" {
			ips, err = readLimited(filepath.Join(directory, "geoip.dat"), 64<<20)
			if err != nil {
				return nil, err
			}
		}
	}
	m, err := routing.CompileMatcherWithOverrides(p, sites, ips, overrides)
	if err != nil {
		return nil, err
	}
	local, err := routing.ParseOverrides(overrides)
	if err != nil {
		return nil, err
	}
	result := &policy{profile: p, matcher: m, dns: newDNSState(), overrides: local}
	for _, action := range []routing.Action{routing.ActionDirect, routing.ActionProxy} {
		if _, err := dnsEndpoints(result, action); err != nil {
			return nil, err
		}
	}
	policyCacheMu.Lock()
	policyCache = cachedPolicyEntry{
		directory:   directory,
		overrides:   overrides,
		profileStat: profileStat,
		geositeStat: geositeStat,
		geoipStat:   geoipStat,
		profile:     p,
		matcher:     m,
		parsed:      local,
	}
	policyCacheMu.Unlock()
	return result, nil
}

func readLimited(path string, limit int64) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("routing file %s: %w", filepath.Base(path), err)
	}
	defer f.Close()
	s, err := f.Stat()
	if err != nil || s.Size() > limit {
		return nil, errors.New("размер routing-файла превышен")
	}
	return os.ReadFile(path)
}

// NormalizeProfile decodes Happ links and validates mandatory fields before caching.
func NormalizeProfile(source string) (string, error) {
	if len(source) > 2<<20 {
		return "", errors.New("routing-профиль слишком большой")
	}
	p, err := routing.ParseProfile(source)
	if err != nil {
		return "", fmt.Errorf("routing format: %w", err)
	}
	b, err := json.Marshal(p)
	return string(b), err
}

func ValidateBundle(directory, overrides string) error {
	_, err := loadPolicy(directory, overrides)
	return err
}

func Classify(directory, overrides, domain, ip string) (string, error) {
	p, err := loadPolicy(directory, overrides)
	if err != nil {
		return "", err
	}
	a, _ := netip.ParseAddr(ip)
	return string(p.matcher.Route(domain, a)), nil
}

func ExplainRoute(directory, overrides, target string) (string, error) {
	p, err := loadPolicy(directory, overrides)
	if err != nil {
		return "", err
	}
	cleaned := strings.TrimSpace(target)
	if strings.Contains(cleaned, "://") {
		if u, err := url.Parse(cleaned); err == nil && u.Hostname() != "" {
			cleaned = u.Hostname()
		}
	}
	cleaned = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(cleaned)), ".")
	if cleaned == "" {
		return "", errors.New("укажите домен или IP-адрес")
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
		b, err := json.Marshal(map[string]any{
			"domain":    ip.String(),
			"action":    string(action),
			"rule":      rule,
			"addresses": []string{ip.String()},
			"paths":     []string{path},
		})
		return string(b), err
	}
	var addresses []string
	var paths []string
	var resolvedAddr netip.Addr
	if hostIP, ok := p.profile.DNSHosts[cleaned]; ok && hostIP != "" {
		if parsedIP, err := netip.ParseAddr(hostIP); err == nil {
			resolvedAddr = parsedIP
			addresses = append(addresses, parsedIP.String())
		}
	}
	action := p.matcher.Route(cleaned, resolvedAddr)
	rule := p.matcher.Trace(cleaned, resolvedAddr)
	for range addresses {
		path := "direct"
		if action == routing.ActionProxy {
			path = "exwg"
		} else if action == routing.ActionBlock {
			path = "blocked"
		}
		paths = append(paths, path)
	}
	b, err := json.Marshal(map[string]any{
		"domain":    cleaned,
		"action":    string(action),
		"rule":      rule,
		"addresses": addresses,
		"paths":     paths,
	})
	return string(b), err
}

// Summary exposes effective DNS/routing settings, not AWG keys or user traffic.
func BundleSummary(directory, overrides string) (string, error) {
	p, err := loadPolicy(directory, overrides)
	if err != nil {
		return "", err
	}
	direct, _ := dnsEndpoints(p, routing.ActionDirect)
	proxy, _ := dnsEndpoints(p, routing.ActionProxy)
	summary := map[string]any{"name": p.profile.Name, "domainStrategy": p.profile.DomainStrategy,
		"globalProxy": bool(p.profile.GlobalProxy), "lastUpdated": p.profile.LastUpdated,
		"fakeDNS": bool(p.profile.FakeDNS), "dnsDirect": direct, "dnsProxy": proxy, "warnings": p.profile.Warnings(),
		"routeOrder": p.profile.RouteOrder, "unknownFields": p.profile.AdditionalUnknownFields,
		"dnsHosts": p.profile.DNSHosts, "geositeURL": p.profile.GeoSiteURL, "geoipURL": p.profile.GeoIPURL}
	summary["expandedRules"] = p.matcher.Counts()
	for _, filename := range []string{"geosite.dat", "geoip.dat"} {
		if stat, err := os.Stat(filepath.Join(directory, filename)); directory != "" && err == nil {
			summary[filename+".bytes"] = stat.Size()
		}
	}
	b, err := json.Marshal(summary)
	return string(b), err
}

