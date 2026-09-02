# Project Courier � Versioning & Release Standard

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
    const val MINOR = 5
    const val PATCH = 0
    const val BUILD_NUMBER = 22
    const val VERSION_NAME = "1.5.0"
    const val RELEASE_DATE = "2026.09.01"
}
```

The root `checkVersionConsistency` task fails the build if this drifts from
`gradle.properties`.

---

## Bump the version FIRST, not last

**The version bump is the first commit of a release cycle, not the last.**

Every build produced during development is stamped with whatever
`courier.versionName` says at the time, and `publishDesktopRelease` copies the
jar matching that version into `release/`. Bump at the end and every build up to
that point is stamped with the *previous* release's number.

This is not hypothetical. During v1.5.0 the bump was left until the final stage,
so eight stages of Device Link work were built and published as
`Courier-Desktop-v1.4.0.jar`. The genuine v1.4.0 artifact was overwritten and
lost. The filename said 1.4.0; the contents were 1.5.0. Anyone reaching for that
build to compare behaviour or roll back would have been debugging a lie.

The v1.4.0 cycle did it correctly — bump in stage 0 — precisely because the
release before *that* was lost to a stale artifact being mistaken for broken
code. Keep doing it that way.

Two supporting guards, both already in place:

- `build/compose/jars` is emptied before packaging, so publishing cannot pick up
  an accumulated jar from an earlier version.
- `checkVersionConsistency` fails the build when `AppVersion.kt` and
  `gradle.properties` disagree.

Neither guard helps if the bump happens after the work. Bump first.

---

## ??? GitHub & Git Tagging Protocol

1. **Git Release Tags**:
   ```bash
   git tag -a v1.1.0 -m "Release v1.1.0 (Build 14)"
   git push origin v1.1.0
   ```

2. **Artifact Naming Standard**:
   - Android APK: `release/Courier-Android-v<VERSION>.apk`
   - Desktop UberJar: `release/Courier-Desktop-v<VERSION>.jar`
   - Desktop Windows Installer: `release/Courier-Installer-v<VERSION>.msi`
   - Plus stable aliases: `Courier-Android-latest.apk`, `Courier-Desktop-latest.jar`

3. **`release/` is disposable.** It is gitignored and regenerable with
   `gradlew publishRelease`. Git tags are the source of truth for what a release
   contained; a binary in that folder is a convenience copy. To recover an old
   build, check out its tag and rebuild rather than trusting a file whose name
   may not match its contents.
