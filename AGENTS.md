# Repository Guidelines

## Project Structure & Module Organization
This repository is a multi-project workspace for SafeGuardian integration work.

- `bitchat-android/`: primary Android app (Kotlin + Jetpack Compose). Main code is in `app/src/main/java`, tests in `app/src/test`.
- `nexa-sdk-examples/android/`: Android reference app for Nexa SDK usage and model workflows.
- `cui-llama.rn/`: React Native + C++ (`llama.cpp`) fork. TypeScript sources in `src/`, native code in `cpp/`, integration example in `example/`.
- Root docs (`README.md`, architecture and phase notes) describe cross-module design decisions.

## Build, Test, and Development Commands
Run commands from the module directory they target.

- `cd bitchat-android && ./gradlew assembleDebug`: build Android debug APK.
- `cd bitchat-android && ./gradlew testDebugUnitTest lintDebug`: run JVM unit tests and Android lint.
- `cd nexa-sdk-examples/android && ./gradlew assembleDebug`: build Nexa Android example app.
- `cd cui-llama.rn && npm install && npm run lint && npm test`: install deps, lint, and run Jest tests.
- `cd cui-llama.rn && npm run typecheck && npm run build`: verify TS types and build library outputs.

## Coding Style & Naming Conventions
- Kotlin/Android: 4-space indentation, `PascalCase` classes/files, `camelCase` methods/properties, `UPPER_SNAKE_CASE` constants.
- TypeScript/JS (`cui-llama.rn`): 2-space indentation, single quotes, semicolon-free style (per Prettier config).
- Keep feature-oriented package structure (for example, `.../mesh`, `.../crypto`, `.../ai`) and colocate tests near their domain.

## Testing Guidelines
- Android app tests use JUnit/Robolectric-style JVM tests under `bitchat-android/app/src/test` with names ending in `Test` (example: `PeerManagerTest.kt`).
- RN module uses Jest (`npm test`) with test files in `src/__tests__`.
- For behavior changes, add or update tests in the same module before opening a PR.

## Commit & Pull Request Guidelines
- Current root history is minimal; existing commits use short, imperative messages (example: `optimize survival mode`).
- Prefer concise, imperative commit subjects and include scope when helpful (for example, `bitchat: fix relay TTL handling`).
- PRs should include: changed module(s), test evidence (commands run), linked issues, and screenshots/video for UI changes.

## Security & Configuration Tips
- Do not commit secrets, keystores, model credentials, or local `*.properties` overrides.
- Preserve offline-first and privacy-focused behavior; document any crypto, transport, or model-loading changes in PR notes.
