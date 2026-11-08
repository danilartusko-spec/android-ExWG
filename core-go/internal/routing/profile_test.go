package routing

import (
	"encoding/base64"
	"testing"
)

const profileJSON = `{
  "Name": "RoscomVPN",
  "GlobalProxy": "true",
  "UseChunkFiles": "true",
  "RemoteDNSIP": "8.8.8.8",
  "DomesticDNSIP": "77.88.8.8",
  "Geoipurl": "https://example.com/geoip.dat",
  "Geositeurl": "https://example.com/geosite.dat",
  "RouteOrder": "block-proxy-direct",
  "DirectSites": ["geosite:category-ru"],
  "DirectIp": ["geoip:private", "10.0.0.0/8"],
  "ProxySites": ["geosite:youtube"],
  "ProxyIp": [],
  "BlockSites": ["geosite:category-ads"],
  "BlockIp": [],
  "FakeDNS": "false"
}`

func TestParsesHappDeepLink(t *testing.T) {
	link := "happ://routing/onadd/" + base64.StdEncoding.EncodeToString([]byte(profileJSON))
	profile, err := ParseProfile(link)
	if err != nil {
		t.Fatal(err)
	}
	if profile.Name != "RoscomVPN" || !bool(profile.GlobalProxy) {
		t.Fatalf("unexpected profile: %#v", profile)
	}
	if len(profile.DirectSites) != 1 || profile.DirectSites[0] != "geosite:category-ru" {
		t.Fatalf("unexpected direct sites: %#v", profile.DirectSites)
	}
}

func TestAcceptsBooleanValues(t *testing.T) {
	profile, err := ParseProfile(`{"Name":"test","GlobalProxy":false,"FakeDNS":true}`)
	if err != nil {
		t.Fatal(err)
	}
	if bool(profile.GlobalProxy) || !bool(profile.FakeDNS) {
		t.Fatalf("unexpected booleans: %#v", profile)
	}
}

func TestRejectsInsecureGeoDataURL(t *testing.T) {
	_, err := ParseProfile(`{"Name":"test","Geoipurl":"http://example.com/geoip.dat"}`)
	if err == nil {
		t.Fatal("expected insecure URL rejection")
	}
}

func TestAcceptsMinimalJSONAndFillsDefaults(t *testing.T) {
	profile, err := ParseProfile(`{"DirectSites":["domain:ru"]}`)
	if err != nil {
		t.Fatalf("expected minimal JSON to parse, got error: %v", err)
	}
	if profile.Name != "Custom" {
		t.Fatalf("expected default Name 'Custom', got %q", profile.Name)
	}
	if !bool(profile.GlobalProxy) {
		t.Fatal("expected GlobalProxy to default to true when ProxySites/ProxyIP are empty")
	}
	if profile.RemoteDNSIP != "8.8.8.8" || profile.DomesticDNSIP != "1.1.1.1" {
		t.Fatalf("unexpected default DNS IPs: remote=%q domestic=%q", profile.RemoteDNSIP, profile.DomesticDNSIP)
	}
}

func TestCaseInsensitiveKnownFieldsDoNotProduceWarnings(t *testing.T) {
	profile, err := ParseProfile(`{"name":"Lower","directsites":["domain:ru"],"globalproxy":true}`)
	if err != nil {
		t.Fatal(err)
	}
	if len(profile.AdditionalUnknownFields) != 0 {
		t.Fatalf("expected 0 unknown fields for lowercase known keys, got %v", profile.AdditionalUnknownFields)
	}
}

func TestParsesEmbeddedHappLinkAndBase64JSON(t *testing.T) {
	encoded := base64.StdEncoding.EncodeToString([]byte(profileJSON))
	fromBase64, err := ParseProfile(encoded)
	if err != nil {
		t.Fatalf("expected base64 JSON to parse: %v", err)
	}
	if fromBase64.Name != "RoscomVPN" {
		t.Fatalf("unexpected name from base64: %q", fromBase64.Name)
	}

	html := `<html><body><a href="happ://routing/onadd/` + encoded + `">Install</a></body></html>`
	fromHTML, err := ParseProfile(html)
	if err != nil {
		t.Fatalf("expected embedded happ link in HTML to parse: %v", err)
	}
	if fromHTML.Name != "RoscomVPN" {
		t.Fatalf("unexpected name from HTML: %q", fromHTML.Name)
	}
}

func TestParsesPlainRuleProfileAndKnownDNSBootstrap(t *testing.T) {
	raw := `
# Custom plain rule profile
direct geosite:category-ru
direct 10.0.0.0/8
proxy geosite:youtube
block domain:ads.example
dns-direct https://common.dot.dns.yandex.net/dns-query
dns-proxy https://dns.google/dns-query
`
	profile, err := ParseProfile(raw)
	if err != nil {
		t.Fatalf("expected plain rule profile to parse: %v", err)
	}
	if profile.Name != "Custom" || !bool(profile.GlobalProxy) {
		t.Fatalf("unexpected plain profile header: %#v", profile)
	}
	if len(profile.DirectSites) != 1 || len(profile.DirectIP) != 1 || len(profile.ProxySites) != 1 || len(profile.BlockSites) != 1 {
		t.Fatalf("unexpected plain profile rules: %#v", profile)
	}
	if profile.DomesticDNSIP != "77.88.8.8" || profile.RemoteDNSIP != "8.8.8.8" {
		t.Fatalf("expected known DNS bootstrap IPs to be filled, got domestic=%q remote=%q", profile.DomesticDNSIP, profile.RemoteDNSIP)
	}
	if DefaultBootstrapIP("example.com") != "" {
		t.Fatal("unknown domain must not have a hardcoded bootstrap IP")
	}
}

