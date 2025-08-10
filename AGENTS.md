# Repository Guidelines

## Project Structure & Module Organization
- `app/`: Android application module (Kotlin + Jetpack Compose).
- `app/src/main/java/me/connor/translateoverlay`: Core code (services, overlay, config).
- `app/src/main/res`: UI resources (`layout/`, `values/`, `drawable/`).
- `app/src/main/assets`: Local config and models (e.g., `local_config.properties`, `sherpa/`).
- `app/src/main/jniLibs`: Native libraries for on-device ASR.
- `app/src/test` and `app/src/androidTest`: Unit and instrumented tests.
- `_docs/`: API/OpenAPI reference; read-only for devs.

## Build, Test, and Development Commands
- Build debug APK: `./gradlew assembleDebug`
- Install on device: `./gradlew installDebug`
- Run unit tests: `./gradlew testDebugUnitTest`
- Run instrumented tests: `./gradlew connectedAndroidTest`
- Lint checks: `./gradlew lint`
- Release bundle: `./gradlew bundleRelease`
Tip: Use Android Studio for Run/Debug; Gradle commands mirror IDE actions.

## Coding Style & Naming Conventions
- Kotlin, Java 11 target; Compose for UI.
- Indentation: 4 spaces; line length ~100–120 where practical.
- Packages: lower case; classes/objects: `PascalCase`; methods/vars: `camelCase`.
- Resources: layouts `activity_main.xml`, drawables `caption_bg.xml`, strings keys `lower_snake_case`.
- Use Android Studio formatter; optimize imports; avoid unused code.

## Testing Guidelines
- Frameworks: JUnit4 (unit), AndroidX Test + Espresso (instrumented).
- Place tests in `app/src/test/...` and `app/src/androidTest/...` mirroring package names.
- Prefer small, deterministic tests for utilities (e.g., `TextProcessingUtils`).
- Aim for coverage on parsing/formatting and service boundaries; mock I/O where possible.
- Run: `./gradlew testDebugUnitTest connectedAndroidTest` before PRs.

## Commit & Pull Request Guidelines
- Commits: concise, imperative mood (e.g., “Improve live STT handling”).
- Group related changes; avoid mixing refactors and features.
- PRs must include: change summary, rationale, screenshots for UI, steps to test, and linked issues.
- Ensure build, tests, and lint pass; note any follow-ups or known limitations.

## Security & Configuration Tips
- Never commit API keys. Configure via `app/src/main/assets/local_config.properties` (see `API_KEY_SETUP.md`).
- Verify overlays/accessibility permissions only at runtime; avoid storing secrets in SharedPreferences.
- Large models/assets belong in `assets/` and should be Git LFS or documented downloads if oversized.
