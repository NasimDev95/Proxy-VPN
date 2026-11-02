<div align="center">

# Proxy-VPN

**A personal Android VPN that routes your device traffic through your own HTTP or SOCKS5 proxy.**

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](#requirements)
[![Min SDK](https://img.shields.io/badge/minSdk-24%20(Android%207.0)-blue)](#requirements)
[![Language](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

</div>

Proxy-VPN creates a local VPN interface on your phone, captures IPv4 traffic, and relays each TCP connection through the proxy you configure. It has no server list and no country picker. You enter one proxy URL, and every app that is not on the bypass list uses it.

> **Use it only on devices and networks you own, or for authorized testing.** This project is not affiliated with Google, Google AI Studio, or any other proxy or security tool.

---

## Features

- **Single editable proxy URL.** Supports `http://host:port` (HTTP CONNECT) and `socks5://host:port`, with optional username and password.
- **Test connection before connecting.** The app opens a CONNECT tunnel to `example.com:443` through your proxy. If the proxy is unreachable, the VPN is not started.
- **Domain-based CONNECT.** DNS queries are resolved over DNS-over-HTTPS (`cloudflare-dns.com:443`) through the proxy. The app caches IP-to-domain mappings with TTL, so the proxy receives hostnames instead of raw IPs. When no mapping exists, the hostname is read from the TLS ClientHello SNI.
- **Fallback DNS.** If the DoH route fails, DNS falls back to a protected UDP query to `1.1.1.1:53`. See [Security notes](#security-notes).
- **Ordered TCP engine.** A user-space TCP stack with sequence-number validation, half-close (FIN) handling, MSS 1360 to avoid fragmentation, and one ordered outbound channel per session.
- **Bypass apps.** Apps in the bypass list use the normal network directly. `com.termux` is bypassed by default, so local proxies such as Termux-hosted tools do not loop back into the VPN. You can add or remove packages in Settings.
- **IPv6 leak protection.** IPv6 routes are captured and dropped, so IPv6 traffic cannot bypass the proxy.
- **Encrypted passwords.** Proxy passwords are encrypted with AES-256-GCM using a key stored in the Android Keystore. `android:allowBackup="false"` keeps app data out of Android backups.
- **In-app diagnostics.** A ring buffer keeps the last 200 log lines (DNS queries, CONNECT requests, errors) and can copy them to the clipboard.
- **Android 14 ready.** The VPN service runs as a foreground service with `foregroundServiceType="specialUse"` and the `vpn` subtype.

---

## How it works

```
Apps on the phone
      │  (all IPv4 traffic, except bypass apps)
      ▼
Android VPN interface (TUN, 10.8.0.2)
      │
      ▼
Tun2ProxyEngine ── UDP port 53 ──► DnsRelay ──► DoH via proxy (fallback: UDP 1.1.1.1)
      │                                  │
      │                                  └── IP ↔ domain mappings ──► DnsCache
      │
      └── TCP ──► TcpRelay (per-session state, ordered writer)
                      │  CONNECT <domain or SNI>:<port>
                      ▼
                 Your proxy (http:// or socks5://)
                      │
                      ▼
                  Internet
```

---

## Requirements

- Android 7.0 (API 24) or newer
- A proxy that supports HTTP `CONNECT` (for `http://`) or SOCKS5 CONNECT (for `socks5://`)
- For building: Android Studio (recent stable version), JDK 17

---

## Install

1. Open the [**Releases**](../../releases) page and download the latest APK.
2. Allow installs from your browser or file manager when Android asks.
3. Open the app, go to **Settings**, enter your proxy URL, and tap **Test connection**.
4. Grant the VPN permission when Android asks for it, then tap **Connect**.

---

## Build from source

```bash
git clone https://github.com/NasimDev95/proxy-vpn.git
cd proxy-vpn
```

1. Open the folder in Android Studio and let Gradle sync.
2. The build file references two signing configs (`release` and `debugConfig`). These need keystores that are not in the repository. For a local debug build, either create your own keystore and point `storeFile` to it, or remove the custom `signingConfigs` block so Android Studio uses its default debug key.
3. Run the app on a device, or build from the terminal:

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
```

Release signing is configured through environment variables: `KEYSTORE_PATH`, `STORE_PASSWORD`, and `KEY_PASSWORD`. Keep your keystore out of git.

---

## Project structure

```
app/src/main/java/com/example/
├── MainActivity.kt
├── data/
│   └── ProxyPreferences.kt        # settings, bypass list, encrypted passwords
├── model/
│   └── ProxyConfig.kt             # URL parsing and validation
├── net/
│   └── ProxyTester.kt             # pre-connection proxy test
├── service/
│   ├── ProxyVpnService.kt         # VpnService, foreground notification, TUN setup
│   ├── VpnController.kt           # start/stop and shared status
│   └── VpnState.kt
├── tunnel/
│   ├── Tun2ProxyEngine.kt         # reads TUN packets, routes UDP DNS and TCP
│   ├── DnsRelay.kt                # DoH via proxy, UDP fallback
│   ├── DnsCache.kt                # IP ↔ domain mappings with TTL
│   ├── SniParser.kt               # SNI from TLS ClientHello
│   ├── TcpRelay.kt                # per-connection relay and handshake
│   ├── TcpSession.kt              # session state
│   ├── PacketUtils.kt             # IPv4, TCP, UDP parsing and building
│   └── TunnelHelpers.kt
├── ui/                            # Jetpack Compose screens, theme, view model
└── util/
    ├── AppLogger.kt               # in-memory log buffer (200 lines)
    └── CryptoManager.kt           # Android Keystore AES-256-GCM
```

---

## Security notes

- **Proxy passwords** are stored encrypted (AES-256-GCM, Android Keystore). They are not stored in plain text.
- **DNS fallback:** if the DoH route through your proxy fails, DNS queries go directly to `1.1.1.1:53` over UDP. Your network operator and anyone on the path can see those queries. Remove the fallback in `DnsRelay.kt` if you need DNS to go only through the proxy.
- **Trust:** the proxy operator can see any traffic that is not end-to-end encrypted. Use HTTPS for sensitive services.
- **Bypass list:** apps on this list are not proxied. Keep banking and other sensitive apps in mind when you change it.

---

## Known limitations

- Only TCP (plus DNS over UDP port 53) is relayed. Other UDP traffic, including QUIC/HTTP3, is not relayed. Browsers normally fall back to TCP.
- IPv6 traffic is dropped, not proxied.
- Only IPv4 TCP connections are tunneled through the proxy.
- No per-app allowlist yet. The bypass list is the only app filter.
- HTTPS (TLS) proxies are not supported. Use `http://` or `socks5://`.
- Some phone vendors (MIUI, HyperOS and others) aggressively kill background services. Turn off battery optimization for this app if the VPN drops in the background.
- Tested on a limited set of devices. Please open an issue with your device model, Android version, and the log output if something breaks.

---

## Testing

Unit tests live in `app/src/test/java/com/example/`:

- `PacketUtilsTest.kt` covers IPv4, UDP and TCP packet building and parsing, the MSS option, and the DNS cache.
- `ProxyConfigTest.kt` covers URL validation for HTTP and SOCKS5, invalid schemes, missing ports, and out-of-range ports.

```bash
./gradlew testDebugUnitTest
```

---

## Contributing

Issues and pull requests are welcome. For bugs, include:

1. Device model and Android version
2. Proxy type (HTTP or SOCKS5) and whether authentication is used
3. The log output copied from the app's diagnostics section
4. Steps to reproduce

---

## Built with

- Kotlin, Coroutines
- Jetpack Compose and Material 3
- Android `VpnService`
- The first version was scaffolded with Google AI Studio.

---

## License

Released under the [MIT License](LICENSE).
