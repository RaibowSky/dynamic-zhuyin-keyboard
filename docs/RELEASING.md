# Stable releases

## Permanent identity

The `distribution` flavor dimension gives two permanent installation identities:

| Flavor | Application ID | Signed by | IME component |
|---|---|---|---|
| `github` | `com.ioszhuyin.keyboard` | the persistent GitHub keystore | `com.ioszhuyin.keyboard/.IOSZhuyinIME` |
| `play` | `dynamic.zhuyin.keyboard` | Google Play app signing | `dynamic.zhuyin.keyboard/com.ioszhuyin.keyboard.IOSZhuyinIME` |

The GitHub identity (package name and signing certificate) is registered in the
Android developer console. Never change its application ID or key: existing
installations, their enabled-IME selection and that registration depend on them.
The Play identity matches the owner's Google Play Console app. The two are
separate apps; they can be installed side by side, do not share data and cannot
update each other.

The Kotlin/resource namespace remains `com.ioszhuyin.keyboard` for both; it is
independent of the installation ID. The fully qualified settings activity remains
`com.ioszhuyin.keyboard.MainActivity`.

## Version policy

The historical `v0.1.0-buildweek` debug demo contained versionName `1.0` and
versionCode `1`. Preserve that tag and asset as a historical prerelease.
The first persistent-key release is `v1.1.0`, versionName `1.1.0`, versionCode `2`.
Every published build increases versionCode; versionName follows SemVer and
the Git tag is exactly `v` plus versionName. Never reuse a code or retag a release.

## Signing

Keep the persistent keystore and passwords outside the checkout, in a protected
directory. Back up both the keystore and its credentials securely: losing either
prevents updates signed with the same identity. Never use the Android debug key.

Create a private `signing.properties` file (example values only):

```properties
storeFile=C:/private/location/release.jks
storePassword=YOUR_SECRET
keyAlias=dynamic-zhuyin
keyPassword=YOUR_SECRET
```

On Windows:

```powershell
$env:ZHUYIN_SIGNING_PROPERTIES = 'C:/private/location/signing.properties'
python tools/rebuild_dictionary.py --check
.\gradlew.bat testGithubDebugUnitTest lintGithubDebug lintPlayDebug assembleGithubRelease bundlePlayRelease
```

On Linux/macOS, set the same environment variable and use `./gradlew`.
Without signing configuration, release packaging fails. Debug CI needs no keys.
The signed GitHub APK is `app/build/outputs/apk/github/release/app-github-release.apk`.
The signed Play upload is `app/build/outputs/bundle/playRelease/app-play-release.aab`.
Never publish a `play` APK on GitHub or upload a `github` bundle to Play.
In Play Console open Closed testing > Alpha > Create new release and upload the
AAB under App bundles. Expand the enabled enhancements and follow App signing
(also under Protect with Play / App integrity) to inspect app-signing and
upload certificates. The local AAB signing certificate must match the registered
upload certificate; if no upload certificate is registered yet, Play displays it
after the first AAB upload. App-signing and upload keys can be different. Do not replace
an existing Play key or send private keystore files to chat or Git.

Use SDK `apksigner verify --verbose --print-certs` to verify the GitHub APK, and
compare the certificate SHA-256 with the previous stable release before publishing.

Test an update on a disposable emulator: install the previous signed APK, then
`adb install -r` the newer signed APK. Verify package version and retained data.
Debug/demo APKs use a different key and cannot be upgraded directly to stable:
export manual dictionary entries first, uninstall the demo, then install stable
and import the export. Exporting learning data requires a separate explicit choice.

## Publish checklist

1. Increase versionCode/versionName, update CHANGELOG.md, and complete tests,
   lint, dictionary reproduction, device checks and signed upgrade verification.
2. Merge the reviewed change and wait for CI on main.
3. Build/sign from that exact commit. Verify the certificate and APK checksum.
4. Tag the commit `v<versionName>` and create a release with meaningful notes,
   the signed APK, and `SHA256SUMS.txt`. No private signing files are uploaded.
5. Verify that the downloadable APK hash matches the locally verified artifact.
6. Update the version and direct APK download link at the top of README.md and
   README.en.md to the newly published release asset.

Font import uses the document picker and keeps a private copy (maximum 20 MB).
Android 10+ `SystemFonts.getAvailableFonts()` exposes font files, not a portable
catalogue of user-facing family names; OEM collections/axes differ. System font
enumeration is deferred. Default + local TTF/OTF import works on API 24+.
