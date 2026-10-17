# APK signing

`test-signing.keystore` is a **public test key** committed on purpose so that every CI build and every
GitHub release is signed the same way. That way a new APK installs over the previous one and your saves
are kept. Because the key is public, anyone could sign an APK that updates over this app. Only install
APKs from this repository's Releases or Actions.

| | |
|---|---|
| store password | `sfrecomp-test` |
| alias | `sfrecomp` |
| key password | `sfrecomp-test` |

To use a private key instead, add these repository secrets: `SIGNING_KEYSTORE_BASE64` (the keystore,
base64-encoded), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. The workflows
use them automatically when they are present. Switching keys means uninstalling the app once, so back up
`battery.sav` first.
