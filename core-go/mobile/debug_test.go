package mobile

import (
	"encoding/json"
	"fmt"
	"strings"
	"syscall"
	"testing"
)

func TestOriginalErrorCodesAndBoundedDebugLog(t *testing.T) {
	detail := errorDetail(fmt.Errorf("dial failed: %w", syscall.ENETUNREACH))
	if !strings.Contains(detail, fmt.Sprintf("errno=%d", int(syscall.ENETUNREACH))) || !strings.Contains(detail, "caused by syscall.Errno") {
		t.Fatal(detail)
	}
	e := &Engine{debug: &debugLog{}}
	for i := 0; i < maxDebugLines+44; i++ {
		e.debug.add("test", fmt.Sprint(i))
	}
	var messages []string
	if err := json.Unmarshal([]byte(e.DrainLogs()), &messages); err != nil {
		t.Fatal(err)
	}
	if len(messages) != maxDebugLines+1 || !strings.Contains(messages[maxDebugLines], "dropped 44") {
		t.Fatal(len(messages))
	}
	if next := e.DrainLogs(); next != "[]" {
		t.Fatal(next)
	}
}

func TestDebugLogKeepsValidUTF8WhenHugeEntryIsBounded(t *testing.T) {
	e := &Engine{debug: &debugLog{}}
	e.debug.add("test", strings.Repeat("я", maxDebugMessageBytes))
	var messages []string
	if err := json.Unmarshal([]byte(e.DrainLogs()), &messages); err != nil {
		t.Fatal(err)
	}
	if len(messages) != 1 || !strings.Contains(messages[0], "entry truncated at 1 MiB") {
		t.Fatal("missing explicit truncation marker")
	}
}
