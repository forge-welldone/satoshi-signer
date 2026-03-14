# Satoshi Signer — App Naming Design

## Decision

**App name:** Satoshi Signer

## Rationale

- **Literally descriptive** — the app signs satoshis (Bitcoin transactions) using a hardware wallet
- **Bitcoin-native** — "Satoshi" is the foundational cultural term; instantly signals the target audience
- **Discoverable** — "Signer" is a searchable keyword on Google Play for users looking for hardware wallet signing tools
- **Hardware-agnostic** — the name makes no reference to Trezor, leaving room for Ledger and other hardware wallet support in the future
- **Unoccupied** — no existing app uses "Satoshi Signer" as a compound name. Note: the "Satoshi" namespace on Google Play is crowded (Wallet of Satoshi, Satoshi App, etc.), but these are all Lightning/custodial wallets — "Satoshi Signer" occupies a distinct niche (hardware wallet signing)

## Naming Constraints (from brainstorming)

| Constraint | Choice |
|------------|--------|
| Vibe | Between technical and branded — Bitcoin-recognizable with personality |
| Hardware reference | Agnostic — no vendor name in the app name |
| Discoverability | Include a keyword — "Signer" |
| Keyword | "Signer" — emphasizes the core action |

## Alternatives Considered

| Name | Pros | Cons | Verdict |
|------|------|------|---------|
| Anvil Signer | Strong metaphor (forging transactions), distinctive icon | Not immediately Bitcoin-associated | Rejected — lacks cultural signal |
| Chisel Signer | Precision metaphor fits single-purpose app | Less weighty, no Bitcoin connection | Rejected |
| Block Signer | Core Bitcoin vocabulary | Confusion with content-blocking apps | Rejected |
| UTXO Signer | Technically precise, appeals to power users | Too niche, excludes less technical users | Rejected |

## Identity

- **App name:** Satoshi Signer
- **Package name:** TBD — depends on domain ownership. If `satoshisigner.com` is registered, use `com.satoshisigner.app`. Otherwise use author's own domain (e.g., `com.yourdomain.satoshisigner`). Must be decided before first Play Store upload (permanent).
- **Play Store short description:** "Sign Bitcoin transactions with your hardware wallet"
- **Play Store long description keywords:** hardware wallet, PSBT, USB-C signing, Trezor, Ledger, Bitcoin transaction, self-custody, multisig — to differentiate from Lightning/custodial wallets that dominate "Satoshi" search results

## Open Items

- **Package name:** Register domain or decide on reverse-DNS convention before implementation
- **App icon / visual identity:** Not covered in this spec — to be decided separately
- **Repository rename:** Current repo is `remote_signer` — consider renaming to `satoshi-signer` for consistency

## Changes Already Applied

- Main design spec title updated to "Satoshi Signer" (`2026-03-14-remote-signer-design.md`)
- User workflow reference updated from "Remote Signer" to "Satoshi Signer"
- Project memory updated
