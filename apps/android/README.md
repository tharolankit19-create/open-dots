# Open Dots Agent for Android

This directory contains the native Android client for Open Dots.

## First-version capabilities

- Native Kotlin + Jetpack Compose tablet/phone UI.
- Provider onboarding for OpenAI, Anthropic, Gemini, OpenRouter, and custom OpenAI-compatible endpoints.
- API keys encrypted with an Android Keystore AES-GCM key.
- Local SQLite persistence for conversation history, scoped tool policies, memory, and audit receipts.
- Scoped permission policy with ASK_EVERY_TIME, ALLOW_ONCE, ALWAYS_ALLOW, and DENY.
- Real installed-app resolution and Android launch intents.
- Optional user-enabled AccessibilityService for visible UI inspection/interaction primitives.
- Visible foreground notification with a Stop action during device control.
- WorkManager-backed local reminders.
- Optional Cloud Browser connection to the existing Open Dots computer runtime. This can use the Docker/Playwright provider or the provider-neutral remote computer adapter.

Cloud Browser credentials are never embedded in the APK. Add the Open Dots server URL and its owner token on-device. Browser navigation still goes through the server approval gateway; Android can approve/deny the returned request and execute it only after approval.

## Build

JDK 17 and Android SDK 35 are required.

    cd apps/android
    gradle testDebugUnitTest assembleDebug

Output:

    app/build/outputs/apk/debug/app-debug.apk

For public distribution, use your own release keystore. Do not commit signing keys.
