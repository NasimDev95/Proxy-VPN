# ProxyVpn

ProxyVpn is a personal Android VPN application (built with Kotlin and Jetpack Compose) that intercepts IPv4 device traffic and tunnels it through a user-configured remote HTTP or SOCKS5 proxy server.

## Features

- **Domain-based CONNECT:** Intercepts DNS queries via DNS-over-HTTPS (DoH) through the proxy (`cloudflare-dns.com:443`) and extracts the TLS Server Name Indication (SNI) from ClientHello packets. All tunnels are established using domain names, completely eliminating TLS certificate errors for sites like Google AI Studio (`https://aistudio.google.com`).
- **Ordered TCP Engine:** Pure-Kotlin user-space TCP stack with sequence number validation, half-close (`FIN`) handling, MSS 1360 tuning to prevent fragmentation, and ordered Channel streams.
- **Bypass Apps:** Automatically bypasses `com.termux` and local proxies to prevent loopback lockups, with an interactive settings panel allowing users to add or remove custom package names.
- **IPv6 Leak Protection:** Captures IPv6 routes and drops unproxied packets to ensure zero IP leaks.
- **Security:** Proxy passwords are encrypted locally using the hardware-backed `AndroidKeyStore` (AES-256-GCM). `allowBackup="false"` is enforced.
- **Live Diagnostics:** Integrated circular log buffer displaying the last 200 lines of DNS lookups, TCP handshakes, and CONNECT requests with one-tap clipboard copy.
- **Pre-Connection Verification:** Verifies proxy reachability via a lightweight handshake before establishing the TUN interface.

## Architecture

- `com.example.service.ProxyVpnService`: Core `VpnService` implementation with Android 14 `specialUse` foreground service type.
- `com.example.tunnel.Tun2ProxyEngine`: Packet coordinator routing UDP DNS to `DnsRelay` and TCP to `TcpRelay`.
- `com.example.tunnel.DnsRelay`: Resolves domain queries over DoH through the proxy, storing mappings in `DnsCache`.
- `com.example.tunnel.TcpRelay`: Manages per-connection TCP sessions, handshakes, and bidirectional streaming.
- `com.example.tunnel.SniParser`: Extracts hostname from TLS ClientHello packets for non-cached destinations.
- `com.example.ui`: Jetpack Compose Material 3 UI with dynamic theme colors, stats counters, and log viewer.
