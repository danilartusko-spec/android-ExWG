package mobile

import (
	"encoding/binary"
	"net/netip"
)

// RFC 9460: retain ALPN, ECH, ports and unknown parameters. Only remove hints
// whose selected route cannot carry that address family. No service overrides.
func filterServiceBinding(data []byte, keep func(netip.Addr) bool) ([]byte, []netip.Addr, bool) {
	if len(data) < 3 {
		return data, nil, false
	}
	pos := 2
	for {
		if pos >= len(data) {
			return data, nil, false
		}
		n := int(data[pos])
		pos++
		if n == 0 {
			break
		}
		if n > 63 || pos+n > len(data) {
			return data, nil, false
		}
		pos += n
	}
	if binary.BigEndian.Uint16(data) == 0 {
		return data, nil, false
	} // AliasMode
	out := append([]byte(nil), data[:pos]...)
	var ips []netip.Addr
	mandatory := make(map[uint16]bool)
	removed := make(map[uint16]bool)
	changed := false
	lastKey := -1
	for pos < len(data) {
		if pos+4 > len(data) {
			return data, nil, false
		}
		key := binary.BigEndian.Uint16(data[pos:])
		n := int(binary.BigEndian.Uint16(data[pos+2:]))
		pos += 4
		if pos+n > len(data) || int(key) <= lastKey {
			return data, nil, false
		}
		lastKey = int(key)
		value := data[pos : pos+n]
		pos += n
		if key == 0 {
			if n%2 != 0 {
				return data, nil, false
			}
			for i := 0; i < n; i += 2 {
				mandatory[binary.BigEndian.Uint16(value[i:])] = true
			}
		}
		if key == 4 || key == 6 {
			size := 4
			if key == 6 {
				size = 16
			}
			if n == 0 || n%size != 0 {
				return data, nil, false
			}
			filtered := make([]byte, 0, n)
			for i := 0; i < n; i += size {
				ip, _ := netip.AddrFromSlice(value[i : i+size])
				if keep(ip) {
					filtered = append(filtered, value[i:i+size]...)
					ips = append(ips, ip)
				} else {
					changed = true
				}
			}
			value = filtered
			if len(value) == 0 {
				removed[key] = true
				continue
			}
		}
		out = append(out, byte(key>>8), byte(key), byte(len(value)>>8), byte(len(value)))
		out = append(out, value...)
	}
	for key := range removed {
		if mandatory[key] {
			return nil, nil, true
		}
	}
	return out, ips, changed
}
