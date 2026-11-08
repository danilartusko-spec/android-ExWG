package mobile

import (
	"context"
	"golang.org/x/net/dns/dnsmessage"
	"io"
	"net/http"
	"nexusandroid/internal/routing"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// Opt-in integration test: never makes network requests in an ordinary go test.
func TestRoutingHelpLive(t *testing.T) {
	if os.Getenv("NEXUS_LIVE_ROUTING") != "1" {
		t.Skip("set NEXUS_LIVE_ROUTING=1 to validate current public routing.help data")
	}
	client := http.Client{Timeout: 45 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Get("https://routing.help/")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	source := response.Header.Get("Location")
	if source == "" {
		body, err := io.ReadAll(io.LimitReader(response.Body, 2<<20))
		if err != nil {
			t.Fatal(err)
		}
		source = string(body)
	}
	p, err := routing.ParseProfile(source)
	if err != nil {
		t.Fatal(err)
	}
	normalized, err := NormalizeProfile(source)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "profile.json"), []byte(normalized), 0600)
	for name, url := range map[string]string{"geosite.dat": p.GeoSiteURL, "geoip.dat": p.GeoIPURL} {
		if url == "" {
			continue
		}
		r, err := client.Get(url)
		if err != nil {
			t.Fatal(err)
		}
		if r.StatusCode != 200 {
			r.Body.Close()
			t.Fatalf("%s HTTP %d", name, r.StatusCode)
		}
		data, err := io.ReadAll(io.LimitReader(r.Body, (64<<20)+1))
		r.Body.Close()
		if err != nil || len(data) > 64<<20 {
			t.Fatal("invalid geo response")
		}
		os.WriteFile(filepath.Join(dir, name), data, 0600)
		t.Logf("%s: %d bytes", name, len(data))
	}
	if err := ValidateBundle(dir, ""); err != nil {
		t.Fatal(err)
	}
	sites, _ := os.ReadFile(filepath.Join(dir, "geosite.dat"))
	ips, _ := os.ReadFile(filepath.Join(dir, "geoip.dat"))
	original, err := routing.CompileMatcher(p, sites, ips)
	if err != nil {
		t.Fatal(err)
	}
	for _, domain := range []string{"yandex.ru", "пример.рф", "service.gov.ru", "youtube.com"} {
		// The provider owns these decisions. Normalization/loading must preserve
		// them, not impose our former country-wide exceptions.
		want := string(original.Domain(domain))
		action, err := Classify(dir, "", domain, "")
		if err != nil || action != want {
			t.Fatalf("%s: %s %v", domain, action, err)
		}
		t.Logf("%s: %s (source profile)", domain, action)
	}
}

func TestYandexDoHLive(t *testing.T) {
	if os.Getenv("NEXUS_LIVE_DNS") != "1" {
		t.Skip("set NEXUS_LIVE_DNS=1 for real public DNS regression")
	}
	for _, source := range []string{"https://77.88.8.8/dns-query 77.88.8.8", "https://common.dot.dns.yandex.net/dns-query 77.88.8.8"} {
		p := dnsTestPolicy(t, `{"Name":"live DNS","GlobalProxy":false}`)
		p.overrides.DNS["dns-direct"] = []string{source}
		e := &Engine{ctx: context.Background(), protector: testProtector{}, debug: &debugLog{}}
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		reply, err := e.exchangeDNS(ctx, p, dnsTestQuery(t, "example.com", dnsmessage.TypeA), routing.ActionDirect)
		cancel()
		p.dns.close()
		if err != nil {
			t.Fatalf("%s: %v", source, err)
		}
		var msg dnsmessage.Message
		if err := msg.Unpack(reply); err != nil || len(msg.Answers) == 0 {
			t.Fatalf("%s: invalid answer %v", source, err)
		}
		t.Logf("%s: answers=%d logs=%s", source, len(msg.Answers), e.DrainLogs())
	}
}
