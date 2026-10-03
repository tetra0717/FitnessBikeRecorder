# PC link protocol v1

Discovery: Android NSD advertises `_fbreco._tcp.` with the service name `FBReco-<device UUID>` and TXT properties `id=<UUID>`, `protocol=1`. The listener binds to the current Wi-Fi IPv4 address and an OS-assigned TCP port. It rebinds and republishes after a network change. There is no LAN subnet scanning or remote cloud service.

The phone creates a persistent random 16-byte pairing key, displayed as 32 hex characters in its PC link dialog. The PC stores the paired UUID and the key encrypted with user-scoped Windows DPAPI. DNS records are hints: trust is established only by signed telemetry matching the paired UUID.

## WebSocket handshake

Connect to `ws://<advertised IPv4>:<advertised port>/bike` with:

- `X-FBReco-Challenge`: 32 random bytes encoded as 64 lowercase hex characters; generated anew for each connection.
- `X-FBReco-Proof`: lowercase hex HMAC-SHA256(key, UTF-8(`connect\n` + challenge)).

The server rejects invalid proofs before upgrading the connection. It accepts at most four authenticated sockets. The client sends a text frame `ping` every two seconds, to keep the server's five-second read timeout alive. WebSocket ping/pong frames are also supported.

## Signed messages

The server sends a text frame approximately once per second, using this JSON envelope:

```json
{"payload":"<exact JSON string>","signature":"<lowercase hex HMAC>"}
```

The signature is HMAC-SHA256(key, UTF-8(`telemetry\n` + challenge + `\n` + payload)). The client verifies the exact payload string before deserializing it, compares signatures in constant time, validates protocol and UUID, and requires a strictly increasing sequence number within each socket. Skipping sequence numbers is allowed for conflated latest-state delivery.

Payload fields:

```json
{"protocol":1,"deviceId":"11111111-1111-1111-1111-111111111111","sequence":1,"bikeConnected":true,"dataFresh":true,"isActive":false,"cadenceRpm":0,"powerWatts":0,"speedKmh":0}
```

`dataFresh` requires a connected BLE bike, a packet less than five seconds old according to Android's monotonic elapsed clock, and cadence and/or power actually present in the packet. Missing optional fields must not be mistaken for zero pedalling. The PC also requires a received, authenticated message less than five seconds old on its own monotonic clock. Unknown state clears dimming immediately. Bluetooth disconnect clears the last sample before reconnect.

Messages authenticate the peer and data; they do not hide telemetry from observers on the LAN. The key is never advertised in DNS or sent in a WebSocket header. Challenge binding and sequence validation prevent a recorded message from being accepted in a different connection or replayed within the same connection.

## Shared test vector

Key: `000102030405060708090a0b0c0d0e0f`. Challenge: 64 zero characters. Payload: the exact single-line JSON above.

Connection proof: `0ddd31746e6498bcb259230029528c2fe895c8c1b343603737dabc8b2315f112`.

Telemetry signature: `5d754cb400ffd57d898398e8a32e795328b90b8c2661fdad018d14640013d65c`.
