# Third-party components

The distributable is a test build. All original notices in imported source are
preserved. Dependency versions are pinned in core-go/go.mod and Gradle files.

| Component | Source/version | License |
| --- | --- | --- |
| AmneziaWG Android config model | amnezia-vpn/amneziawg-android, 5c16489 | Apache-2.0 |
| AmneziaWG Go | amnezia-vpn/amneziawg-go/v3 v3.1.20260814 | MIT, per-file upstream notices |
| gVisor netstack | gvisor.dev/gvisor 89a5d21be8f0 | Apache-2.0 |
| Go runtime / x packages / gomobile | golang.org, versions in go.mod | BSD-3-Clause |
| Google B-tree | github.com/google/btree v1.1.3 | Apache-2.0 |
| Protocol Buffers Go | google.golang.org/protobuf v1.36.8 | BSD-3-Clause |
| Geodata protobuf schema only | XTLS/Xray-core c1958dba04ba | MPL-2.0 |
| AndroidX / Compose / WorkManager | Google AndroidX, versions in Gradle | Apache-2.0 |
| Kotlin and coroutines | JetBrains, versions in Gradle | Apache-2.0 |
| OkHttp / Okio | Square, OkHttp 4.12.0 | Apache-2.0 |
| ZXing Android Embedded | journeyapps/zxing-android-embedded v4.3.0 | Apache-2.0 |
| ZXing core | zxing/zxing zxing-3.5.3 | Apache-2.0 |
| Desugared Java libraries | google/desugar_jdk_libs 2.1.5, https://github.com/google/desugar_jdk_libs | GPL-2.0 with Classpath Exception and per-file notices; DESUGAR-JDK-LICENSE |
| Supplemental public CA certificates | ISRG Root X1/X2, https://letsencrypt.org/certificates/ | Public CA certificate data |

The Xray engine, Wireproxy, Wintun and Windows APIs are NOT part of the Android
runtime. Wintun can appear in the upstream Go module graph but is excluded by
Android build tags. Only geodat.proto and generated schema definitions are copied
from Xray; their unmodified source and MPL license accompany this project in
core-go/internal/geodata. This is not an Xray-based VPN engine.

The Ex WG app icon is reused from the desktop Ex WG project.
Remote routing data is downloaded from the user's selected source, not bundled
or relabeled. Check that provider's terms before public redistribution of data.

Copies of major third-party license texts are packaged in app/src/main/assets/licenses.
The complete source for this build is this Android directory, including the
MPL-covered schema. Any public redistribution should provide corresponding
source and preserve these notices.
