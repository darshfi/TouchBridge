# TouchBridge APDU dialect v1

All sizes in bytes. Short APDUs only, logical channel zero; no chaining or extended APDUs. `Le=00` means up to 256 bytes. SELECT accepts either no Le or Le=00; GET_SESSION requires Le=00. Commands with unexpected lengths/parameters are rejected rather than partially parsed.

The 13-byte AID is `F0 54 4F 55 43 48 42 52 49 44 47 45 01` (Lc=0D). The wire SELECT is:

```
00 A4 04 00 0D F0 54 4F 55 43 48 42 52 49 44 47 45 01 00
```

Successful SELECT returns `90 00` with no body. GET_SESSION (`80 10 00 00 00`) requires a successful SELECT within the same activated link and current session generation. Its body has 44 bytes: version=01, 16-byte random ID, 4-byte remaining TTL in milliseconds, and the 23-byte UTF-8 string `Hello from TouchBridge!`. It ends with `90 00` (46 total). TTL is 1..60000; zero IDs, different versions, invalid greeting bytes, appended/truncated data, and invalid TTLs are rejected by the reader.

| Status | Meaning |
| --- | --- |
| 9000 | Success |
| 6700 | Wrong length / incomplete framing |
| 6985 | No active session or session expired |
| 6986 | SELECT required, or selection belongs to an old session |
| 6A82 | Unknown AID |
| 6A86 | Unsupported parameters / Le |
| 6D00 | Unknown instruction |
| 6E00 | Unknown CLA |

Deactivation clears selection. Failed SELECT clears prior selection. Interrupted links can reselect the current unexpired session; they do not extend its deadline. Repeated GETs are allowed for retry until expiry. Stop/restart changes generation and ID, requiring re-selection. Random generation takes place on explicit user session creation, never inside processCommandApdu.

No remote timestamps are trusted. Reader TTL is advisory and must become a monotonic receipt-time deadline when Nearby is introduced. This protocol does not authenticate the peer, encrypt metadata, or transport files.
