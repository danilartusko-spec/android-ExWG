package mobile

import (
	"crypto/tls"
	"crypto/x509"
	"embed"
	"sync"
	"sync/atomic"
)

// Only established ISRG roots, downloaded from the CA's official repository.
// No leaf/intermediate pinning and no disabled certificate/hostname checks.
//
//go:embed roots/*.pem
var supplementalRoots embed.FS
var legacyTrust atomic.Bool
var legacyRootOnce sync.Once
var legacyRootPool *x509.CertPool

// ConfigureLegacyTrust is called by Android < 8, whose system trust stores may
// predate ISRG. Newer systems retain their unmodified trust policy.
func ConfigureLegacyTrust(enabled bool) { legacyTrust.Store(enabled) }

func clientTLSConfig(serverName string) *tls.Config {
	config := &tls.Config{MinVersion: tls.VersionTLS12, ServerName: serverName}
	if legacyTrust.Load() {
		legacyRootOnce.Do(func() {
			legacyRootPool, _ = x509.SystemCertPool()
			if legacyRootPool == nil {
				legacyRootPool = x509.NewCertPool()
			}
			for _, name := range []string{"isrgrootx1.pem", "isrgrootx2.pem"} {
				pem, err := supplementalRoots.ReadFile("roots/" + name)
				if err != nil || !legacyRootPool.AppendCertsFromPEM(pem) {
					panic("invalid bundled CA root")
				}
			}
		})
		config.RootCAs = legacyRootPool
	}
	return config
}
