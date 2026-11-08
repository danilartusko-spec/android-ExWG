package mobile

import (
	"bytes"
	"encoding/binary"
	"net"
	"strings"
	"time"

	"golang.org/x/net/idna"
)

const sniffLimit = 32768

// Inspect only the initial, plaintext protocol metadata. Never decrypt TLS,
// install certificates or replace the destination with a Host/SNI value.
// Every consumed byte (including incomplete/unknown protocols) is replayed.
func sniffTCP(c net.Conn) ([]byte, string) {
	_ = c.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	defer c.SetReadDeadline(time.Time{})
	data := make([]byte, 0, 2048)
	for len(data) < sniffLimit {
		buf := make([]byte, min(2048, sniffLimit-len(data)))
		n, err := c.Read(buf)
		data = append(data, buf[:n]...)
		domain, more := sniffDomain(data)
		if domain != "" || !more || err != nil {
			return data, domain
		}
	}
	return data, ""
}

func sniffDomain(data []byte) (string, bool) {
	if len(data) == 0 {
		return "", true
	}
	if data[0] == 22 {
		return sniffTLS(data)
	}
	methods := []string{"GET ", "HEAD ", "POST ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT "}
	possible := false
	for _, method := range methods {
		if bytes.HasPrefix(data, []byte(method)) || bytes.HasPrefix([]byte(method), data) {
			possible = true
			break
		}
	}
	if !possible {
		return "", false
	}
	end := bytes.Index(data, []byte("\r\n\r\n"))
	if end < 0 {
		return "", true
	}
	var host string
	for _, line := range strings.Split(string(data[:end]), "\r\n")[1:] {
		key, value, ok := strings.Cut(line, ":")
		if ok && strings.EqualFold(key, "host") {
			if host != "" {
				return "", false
			} // ambiguous headers are not route evidence
			host = strings.TrimSpace(value)
		}
	}
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	return routeHostname(host), false
}

func sniffTLS(data []byte) (string, bool) {
	var handshake []byte
	for offset := 0; offset < len(data); {
		if len(data)-offset < 5 {
			return "", true
		}
		header := data[offset : offset+5]
		if header[0] != 22 || header[1] != 3 {
			return "", false
		}
		size := int(binary.BigEndian.Uint16(header[3:]))
		if size == 0 || size > 18432 || len(handshake)+size > sniffLimit {
			return "", false
		}
		if len(data)-offset-5 < size {
			return "", true
		}
		handshake = append(handshake, data[offset+5:offset+5+size]...)
		offset += size + 5
		if len(handshake) < 4 {
			continue
		}
		if handshake[0] != 1 {
			return "", false
		}
		length := int(handshake[1])<<16 | int(handshake[2])<<8 | int(handshake[3])
		if length > sniffLimit-4 {
			return "", false
		}
		if len(handshake) >= length+4 {
			return clientHelloName(handshake[4 : 4+length]), false
		}
	}
	return "", true
}

func clientHelloName(body []byte) string {
	if len(body) < 35 {
		return ""
	}
	offset := 35 + int(body[34]) // version, random, session id
	if offset+2 > len(body) {
		return ""
	}
	offset += 2 + int(binary.BigEndian.Uint16(body[offset:]))
	if offset >= len(body) {
		return ""
	}
	offset += 1 + int(body[offset]) // compression methods
	if offset+2 > len(body) {
		return ""
	}
	end := offset + 2 + int(binary.BigEndian.Uint16(body[offset:]))
	offset += 2
	if end != len(body) {
		return ""
	}
	var hostname string
	for offset+4 <= end {
		kind := binary.BigEndian.Uint16(body[offset:])
		size := int(binary.BigEndian.Uint16(body[offset+2:]))
		offset += 4
		if offset+size > end {
			return ""
		}
		if kind == 0xfe0d {
			return ""
		} // ECH outer name is not the inner destination.
		if kind == 0 {
			if hostname != "" || size < 5 {
				return ""
			}
			ext := body[offset : offset+size]
			if int(binary.BigEndian.Uint16(ext)) != len(ext)-2 {
				return ""
			}
			for i := 2; i < len(ext); {
				if i+3 > len(ext) {
					return ""
				}
				n := int(binary.BigEndian.Uint16(ext[i+1:]))
				typ := ext[i]
				i += 3
				if i+n > len(ext) {
					return ""
				}
				if typ == 0 {
					if hostname != "" {
						return ""
					}
					hostname = routeHostname(string(ext[i : i+n]))
				}
				i += n
			}
		}
		offset += size
	}
	if offset != end {
		return ""
	}
	return hostname
}

func routeHostname(value string) string {
	value = strings.TrimSuffix(strings.ToLower(value), ".")
	name, err := idna.Lookup.ToASCII(value)
	if err != nil || len(name) == 0 || len(name) > 253 || net.ParseIP(name) != nil {
		return ""
	}
	for _, label := range strings.Split(name, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return ""
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
				return ""
			}
		}
	}
	return name
}
