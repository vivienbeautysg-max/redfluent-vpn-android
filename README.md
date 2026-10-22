# RedFluent VPN for Android

RedFluent VPN is a private, invite-only secure tunnel client for Android.

## License

Copyright (C) 2026 Redfluent Pte. Ltd.

This program is free software: you can redistribute it and/or modify it under
the terms of the **GNU General Public License version 3** as published by the
Free Software Foundation, either version 3 of the License, or (at your option)
any later version. See [LICENSE](LICENSE) for the full text.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.

### Why GPLv3

This app links against **libbox**, built from
[sing-box](https://github.com/SagerNet/sing-box) (Copyright (C) 2022 by
nekohasekai, licensed under GPLv3). Because the shipped APK is a derivative
work of that code, this client is released under the same license, and its
complete corresponding source is published here.

sing-box is an independent project. This app is **not** affiliated with,
endorsed by, or associated with sing-box or its authors.

### What is not in this repository

- `libs/libbox.aar` — the engine binary is not committed because of its size.
  It is built with gomobile from the sing-box source
  (https://github.com/SagerNet/sing-box) and dropped into `libs/`.
- Signing keys (`*.jks`, `keystore.properties`) — release builds are signed by
  Redfluent; you can build and sign your own.
- Server-side components (the backend API at `vpn-api.redfluent.com`, server
  deployment configuration and operational credentials) are separate programs
  that are not distributed with this app and are not covered by this license.

Runtime VPN configuration is fetched from the backend after activation; no
server credentials are contained in this repository. An invite code is
required to activate the app.

## Building

```
./gradlew assembleDebug        # debug build, package com.redfluent.vpn.debug
./gradlew assembleRelease      # needs your own keystore.properties + keystore
```

Requires Android SDK 35, JDK 17 and `libs/libbox.aar` (see above).

## Downloads

Signed release APKs: https://github.com/vivienbeautysg-max/redfluent-downloads/releases
