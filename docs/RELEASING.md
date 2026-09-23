# Stable releases

## Permanent identity

Keep application ID / namespace `com.ioszhuyin.keyboard` and the registered
`.IOSZhuyinIME` component. Existing installations and Android's enabled-IME
selection refer to these identifiers. Renaming them offers no user benefit and
would create a different app or invalidate that selection. The Gradle project
and resource theme names are now DynamicZhuyinKeyboard / Theme.DynamicZhuyin.
This is a deliberate compatibility decision for issue #7.

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
.\gradlew.bat testDebugUnitTest lintDebug assembleRelease
```

On Linux/macOS, set the same environment variable and use `./gradlew`.
Without signing configuration, release packaging fails. Debug CI needs no keys.
The signed output is `app/build/outputs/apk/release/app-release.apk`.
Use SDK `apksigner verify --verbose --print-certs` to verify it, and compare the
certificate SHA-256 with the previous stable release before publishing.

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

Font import uses the document picker and keeps a private copy (maximum 20 MB).
Android 10+ `SystemFonts.getAvailableFonts()` exposes font files, not a portable
catalogue of user-facing family names; OEM collections/axes differ. System font
enumeration is deferred. Default + local TTF/OTF import works on API 24+.
