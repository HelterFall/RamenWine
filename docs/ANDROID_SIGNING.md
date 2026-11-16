# Permanent Android APK signing

RamenWine CI signs every installable debug APK with one persistent PKCS#12
keystore. The private key is never committed to this repository.

## One-time GitHub setup

1. Open **Settings → Secrets and variables → Actions** for
   `HelterFall/RamenWine`.
2. Create a repository secret named
   `RAMENWINE_SIGNING_BUNDLE_BASE64`.
3. Set its value to the complete single-line content of the
   `RAMENWINE_SIGNING_BUNDLE_BASE64.txt` file supplied privately to the
   repository owner.
4. Re-run the failed Actions workflow with **Re-run all jobs** after saving
   the secret.

The CI preflight validates the bundle, keystore password, alias and certificate
fingerprint before starting the expensive rootfs and DirectAudio jobs.

## Permanent certificate

SHA-256:

`2F:CA:6E:BF:BD:8C:53:96:D6:84:DB:93:0F:BA:3F:3E:E8:F4:29:5D:FC:81:1C:A0:37:6A:C1:6D:BC:F7:74:6A`

CI verifies the APK's actual v2/v3 signing certificate against this fingerprint
before uploading the APK artifact. The Gradle build also fails on CI if the
persistent signing key was not prepared.

The keystore is PKCS#12 with alias `ramenwine`. Keep the downloaded secret
bundle in a private password manager or other private backup. Do not commit it,
upload it as a workflow artifact, or paste it into an issue or pull request.

## Android update compatibility

The Android package ID remains `dev.ramenwine`. CI sets `versionCode` to the
GitHub Actions run number, so each successful build is newer than the previous
CI build.

Builds #413, #417 and #429 were signed by different temporary GitHub-runner debug
keys. Their private keys were not retained. Build #429 is the most recent PR-branch
APK produced without the persistent signing configuration. The certificate fingerprints were:

- #413: `DF:4B:0D:04:72:1B:B3:69:67:A8:E7:AF:AE:F9:C8:2D:40:DF:CE:15:6C:85:EC:A0:C7:21:52:13:EA:90:F7:56`
- #417: `F9:E8:3F:28:CE:6A:E8:B6:8F:39:78:46:9E:04:E2:B4:4A:73:16:85:B9:28:37:49:41:36:7F:38:12:F7:72:8C`
- #429: `21:08:7B:1E:18:2F:8B:5B:E5:09:6F:4E:91:E0:98:C2:92:CD:69:E4:E4:29:60:B7:99:59:B8:14:31:AF:6B:AB`

Android does not allow an APK signed with the new permanent key to replace an
already-installed #413/#417 build. This one-time legacy transition requires
removing the old app before installing the first permanent-key build. Removing
the app deletes its private app data. Keep the original game folders outside
the app before doing this; after installing the permanent-key build, import
those folders again. Once the first permanent-key APK is installed, subsequent
CI builds use the same certificate and can update it in place.
