package mobile

import (
	"path/filepath"
	"testing"
)

func TestBundledRoutingSnapshotIsCompleteAndUsable(t *testing.T) {
	directory := filepath.Join("..", "..", "app", "src", "main", "assets", "routing-bootstrap")
	policy, err := loadPolicy(directory, "")
	if err != nil {
		t.Fatalf("bundled routing snapshot: %v", err)
	}
	counts := policy.matcher.Counts()
	t.Logf("expanded bundled routing rules: %#v", counts)
	for _, action := range []string{"direct", "proxy", "block"} {
		values := counts[action]
		if values["domains"]+values["ipPrefixes"] == 0 {
			t.Fatalf("bundled %s rules are empty: %#v", action, values)
		}
	}
}
