# Third-party notices

The self-contained Windows executable includes these libraries:

| Library | Version | License / copyright |
| --- | --- | --- |
| Makaretu.Dns.Multicast | 0.27.0 | MIT, Richard Schneider |
| Makaretu.Dns | 2.0.1 | MIT, Richard Schneider |
| IPNetwork2 | 2.1.2 | BSD, lduchosal |
| Common.Logging / Common.Logging.Core | 3.4.1 | Apache 2.0, Common.Logging contributors |
| SimpleBase | 1.3.1 | Apache 2.0, Copyright 2014–2017 Sedat Kapanoglu |
| Tmds.LibC | 0.2.0 | MIT, Tom Deseyn |
| .NET / Windows Desktop Runtime | 10.0 | MIT and third-party components, Microsoft and contributors |

License texts are in the `licenses` folder. Runtime license and third-party notices are copied from the exact runtime packages used for the release.

The Android APK also includes NanoHTTPD / NanoWSD 2.3.1 under a BSD license. Its license text is included in the source at `docs/licenses/NanoHTTPD-BSD.txt`. Other Android dependencies are unchanged from the existing application's dependency family; their corresponding notices should be retained for production distribution.
