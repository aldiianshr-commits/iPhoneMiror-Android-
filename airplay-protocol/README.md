# airplay-protocol

Vendored subset of the `protocol` module from
[serezhka/java-airplay](https://github.com/serezhka/java-airplay) (MIT License,
see `LICENSE-java-airplay`).

Changes made for Android:

- Removed Lombok and SLF4J (replaced with `internal/ProtoLog`).
- Replaced `java.util.HexFormat` (Android API 34+) with a local hex helper.
- FairPlay lookup tables moved into the package resource path to avoid
  collisions at the APK root.
- Replaced the GPL-licensed `curve25519-java` dependency with a local RFC 7748
  X25519 implementation (`crypto/X25519`, verified against RFC test vectors).
- `PairingIdentity` can be persisted/restored from its Ed25519 seed so the
  receiver keeps a stable identity across restarts.

Only the legacy pairing + FairPlay SAP v3 receiver path is included. This code
is based on public reverse-engineering work of the AirPlay protocol; it is not
an Apple-provided SDK.
