# Future milestone: device identities and trust

Developer idea recorded on 2026-10-28: each TouchBridge installation should have a lasting, unique identity so users can add devices as trusted. This is deferred until core sharing works; v0.3.0 still requires the code comparison and approval on both phones.

Proposed design:

- Generate a per-installation signing key in Android Keystore. Derive the public device ID/fingerprint from its public key. Keep it stable across ordinary app updates; do not use IMEI, MAC address, Android ID, or another hardware/tracking identifier.
- A public ID is a label, not authentication. A remembered device must prove possession of its private key using a fresh, session-bound challenge over the approved encrypted link. Never trust an endpoint name or a replayed ID.
- Add a device to trust only after explicit code comparison and an explicit Save as trusted action. Store its public key and a user-editable name locally. Show a changed-key warning and require fresh approval rather than silently replacing keys.
- Provide a trusted-device list with rename, revoke and reset identity actions. Define which actions trust enables; receiving sensitive files should continue to require meaningful consent.
- Avoid broadcasting permanent public IDs during discovery/NFC. Keep ephemeral pairing IDs and disclose stable identity only inside the approved link.
- Reinstallation, clearing app data, or loss of the Keystore key creates a new identity. Cross-install identity recovery/export needs a separate explicit design; do not promise a hardware-permanent ID.

No persistent identity, trust database, automatic acceptance, or recovery mechanism is implemented yet.
