# Security Policy

## Reporting a Vulnerability

Flare is designed for people in high-risk environments. Security vulnerabilities can put lives at risk. We take every report seriously.

**Do not open a public GitHub issue for security vulnerabilities.**

Instead, please report vulnerabilities by emailing the maintainer directly or by using GitHub's private vulnerability reporting feature:

1. Go to the [Security tab](https://github.com/zivelo1/Flare/security) of this repository
2. Click "Report a vulnerability"
3. Provide as much detail as possible

### What to Include

- Description of the vulnerability
- Steps to reproduce
- Impact assessment (what an attacker could do)
- Suggested fix (if you have one)

### Response Timeline

- **Acknowledgment** — within 48 hours
- **Initial assessment** — within 1 week
- **Fix or mitigation** — as fast as possible, depending on severity

### Scope

The following are in scope:
- Cryptographic weaknesses (key management, encryption, signatures)
- Message privacy (eavesdropping, metadata leakage)
- BLE/Wi-Fi Direct transport vulnerabilities
- Database encryption bypass
- Duress PIN detection (forensic distinguishability)
- Traffic analysis attacks
- Relay node attacks (message manipulation, replay)

## Security Architecture

Flare uses established, audited cryptographic primitives:
- **Ed25519** — digital signatures
- **X25519** — Diffie-Hellman key agreement
- **AES-256-GCM** — authenticated encryption
- **HKDF-SHA256** — key derivation
- **Argon2id** — passphrase-based key derivation
- **SQLCipher** — encrypted database at rest
- **Sender Keys** — O(1) group encryption with chain ratchet

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full security model diagram.
