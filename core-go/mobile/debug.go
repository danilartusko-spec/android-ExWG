package mobile

import (
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"syscall"
	"time"
	"unicode/utf8"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"
)

// Bounded, local-only diagnostics. Never pass UAPI settings or IpcGet to this log.
type debugLog struct {
	mu      sync.Mutex
	lines   []string
	bytes   int
	dropped int
}

const (
	maxDebugLines        = 4096
	maxDebugBytes        = 4 * 1024 * 1024
	maxDebugMessageBytes = 1024 * 1024
)

func (l *debugLog) add(stage, message string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(message) > maxDebugMessageBytes {
		limit := maxDebugMessageBytes
		for limit > 0 && !utf8.ValidString(message[:limit]) {
			limit--
		}
		message = message[:limit] + " [entry truncated at 1 MiB before Android archive]"
	}
	line := time.Now().Format(time.RFC3339Nano) + " " + stage + " " + message
	for len(l.lines) > 0 && (len(l.lines) >= maxDebugLines || l.bytes+len(line) > maxDebugBytes) {
		l.bytes -= len(l.lines[0])
		l.lines = l.lines[1:]
		l.dropped++
	}
	l.lines = append(l.lines, line)
	l.bytes += len(line)
}

func errorDetail(err error) string {
	if err == nil {
		return ""
	}
	result := fmt.Sprintf("%T: %v", err, err)
	var code interface{ ErrorCode() int64 }
	if errors.As(err, &code) {
		result += fmt.Sprintf("; uapi_errno=%d", code.ErrorCode())
	}
	var errno syscall.Errno
	if errors.As(err, &errno) {
		result += fmt.Sprintf("; errno=%d", int(errno))
	}
	for cause, depth := errors.Unwrap(err), 0; cause != nil && depth < 16; cause, depth = errors.Unwrap(cause), depth+1 {
		result += fmt.Sprintf("\ncaused by %T: %v", cause, cause)
	}
	return result
}

func (e *Engine) logError(stage string, err error) {
	if err != nil && e.debug != nil {
		e.debug.add(stage, errorDetail(err))
	}
}

func (e *Engine) logInfo(stage, message string) {
	if e.debug != nil {
		e.debug.add(stage, message)
	}
}

// DrainLogs moves original core messages to Android's persistent daily archive.
func (e *Engine) DrainLogs() string {
	if e.debug == nil {
		return "[]"
	}
	e.debug.mu.Lock()
	defer e.debug.mu.Unlock()
	if len(e.debug.lines) == 0 {
		return "[]"
	}
	lines := e.debug.lines
	if e.debug.dropped > 0 {
		lines = append(lines, fmt.Sprintf("debug buffer dropped %d oldest messages", e.debug.dropped))
	}
	b, _ := json.Marshal(lines)
	e.debug.lines = nil
	e.debug.bytes = 0
	e.debug.dropped = 0
	return string(b)
}

// Requests a rekey without rebuilding the TUN, resetting counters or deleting
// the current keypair. Official AWG owns throttling and retransmit timers.
func (e *Engine) RequestHandshake() error {
	if e.closed.Load() {
		return errors.New("handshake: engine closed")
	}
	if e.peer == nil {
		return errors.New("handshake: peer missing")
	}
	err := e.peer.SendHandshakeInitiation(false)
	e.logError("handshake.request", err)
	return err
}

// RefreshNetwork keeps the Android TUN and all routed application state, but
// replaces the physical UDP bind. This is required after Wi-Fi/mobile handover:
// a protected socket is outside Android's VPN, yet can still belong to the old
// physical network. peerSettings contains public-key/endpoint pairs only and is
// allowed to be empty when endpoint DNS refresh was unavailable.
func (e *Engine) RefreshNetwork(peerSettings string) error {
	if e.closed.Load() {
		return errors.New("network refresh: engine closed")
	}
	e.networkMu.Lock()
	defer e.networkMu.Unlock()
	if e.closed.Load() {
		return errors.New("network refresh: engine closed")
	}
	if err := e.device.BindUpdate(); err != nil {
		e.logError("network.bind", err)
		return fmt.Errorf("network refresh bind: %w", err)
	}
	if peerSettings != "" {
		if err := e.device.IpcSet(peerSettings); err != nil {
			e.logError("network.endpoint", err)
			return fmt.Errorf("network refresh endpoint: %w", err)
		}
	}
	if p := e.policy.Load(); p != nil {
		p.dns.resetNetwork()
	}
	if e.peer == nil {
		return errors.New("network refresh: peer missing")
	}
	if err := e.peer.SendHandshakeInitiation(false); err != nil {
		e.logError("network.handshake", err)
		return err
	}
	e.logInfo("network.refresh", "physical UDP bind reopened; handshake requested")
	return nil
}

func coreLogger(log *debugLog) *device.Logger {
	return &device.Logger{
		Verbosef: func(format string, args ...any) { log.add("AWG.verbose", fmt.Sprintf(format, args...)) },
		Errorf:   func(format string, args ...any) { log.add("AWG.error", fmt.Sprintf(format, args...)) },
	}
}
