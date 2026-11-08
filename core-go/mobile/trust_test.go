package mobile

import (
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/pem"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestLegacyTrustPreservesTLSValidation(t *testing.T) {
	for name, expected := range map[string]string{
		"isrgrootx1.pem": "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6",
		"isrgrootx2.pem": "69729b8e15a86efc177a57afb7171dfc64add28c2fca8cf1507e34453ccb1470",
	} {
		data, _ := supplementalRoots.ReadFile("roots/" + name)
		block, _ := pem.Decode(data)
		if block == nil {
			t.Fatal("invalid root")
		}
		digest := sha256.Sum256(block.Bytes)
		if hex.EncodeToString(digest[:]) != expected {
			t.Fatal("unexpected root fingerprint")
		}
	}
	ConfigureLegacyTrust(true)
	defer ConfigureLegacyTrust(false)
	config := clientTLSConfig("")
	if config.InsecureSkipVerify || config.RootCAs == nil || config.MinVersion < tls.VersionTLS12 {
		t.Fatal("unsafe legacy TLS")
	}
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) }))
	defer server.Close()
	transport := &http.Transport{TLSClientConfig: config}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport}
	if response, err := client.Get(server.URL); err == nil {
		response.Body.Close()
		t.Fatal("self-signed certificate accepted")
	}
	valid := config.Clone()
	valid.RootCAs = valid.RootCAs.Clone()
	valid.RootCAs.AddCert(server.Certificate())
	validTransport := &http.Transport{TLSClientConfig: valid}
	defer validTransport.CloseIdleConnections()
	response, err := (&http.Client{Transport: validTransport}).Get(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	wrongHost := valid.Clone()
	wrongHost.ServerName = "not-the-server.example"
	wrongTransport := &http.Transport{TLSClientConfig: wrongHost}
	defer wrongTransport.CloseIdleConnections()
	if response, err := (&http.Client{Transport: wrongTransport}).Get(server.URL); err == nil {
		response.Body.Close()
		t.Fatal("wrong hostname accepted")
	}
	ConfigureLegacyTrust(false)
	if clientTLSConfig("").RootCAs != nil {
		t.Fatal("modern system roots overridden")
	}
}
