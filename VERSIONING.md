# Project Courier — Versioning & Release Standard

Project Courier follows **Semantic Versioning (SemVer 2.0.0)** combined with monotonically incrementing build numbers.

---

## ?? Version Format

```
v<MAJOR>.<MINOR>.<PATCH> (Build <BUILD_NUMBER>)
Example: Courier v1.1.0 (Build 14)
```

- **MAJOR (`1.x.x`)**: Incompatible API or major structural redesigns.
- **MINOR (`x.1.x`)**: New user-facing features, engine updates, UI overhauls (backward-compatible).
- **PATCH (`x.x.1`)**: Bug fixes, extractor regex updates, styling tweaks.
- **BUILD_NUMBER (`14`)**: Monotonically incrementing integer bumped on every release build.

---

## ??? Single Source of Truth

Version metadata is centrally maintained in `shared/src/commonMain/kotlin/courier/util/AppVersion.kt`:

```kotlin
object AppVersion {
    const val MAJOR = 1
    const val MINOR = 1
    const val PATCH = 0
    const val BUILD_NUMBER = 14
    const val VERSION_NAME = "1.1.0"
    const val RELEASE_DATE = "2026.08.30"
}
```

---

## ??? GitHub & Git Tagging Protocol

1. **Git Release Tags**:
   ```bash
   git tag -a v1.1.0 -m "Release v1.1.0 (Build 14)"
   git push origin v1.1.0
   ```

2. **Artifact Naming Standard**:
   - Android APK: `release/Courier-Android-v1.1.0.apk`
   - Desktop UberJar: `release/Courier-Desktop-v1.1.0.jar`
   - Desktop Windows Installer: `release/Courier-Installer-v1.1.0.msi`
