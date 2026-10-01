# Android Agent + Cloud Browser Architecture

Open Dots now treats execution as provider-neutral nodes instead of letting the model call raw OS APIs.

## Local Android node

The Android client owns:

- provider configuration and encrypted provider secrets
- conversation persistence
- memory
- permission policies
- device audit receipts
- Android application resolution/launch
- optional accessibility observation and input primitives
- visible cancellation via a foreground-service Stop action
- WorkManager reminders

A persistent permission is keyed by capability + target. An open-app permission for WhatsApp therefore does not grant send-message, read-screen, or other WhatsApp capabilities.

## Cloud Browser node

The Android client can connect to the existing authenticated Open Dots computer API.

That server already supports a provider-neutral computer contract with:

- fake provider for deterministic tests
- isolated Docker/Playwright provider
- remote HTTP computer provider for hosted browser infrastructure

The Android client never receives the remote runtime provider credential. It stores only the user's Open Dots owner token in Android Keystore-backed encrypted storage.

Browser navigation remains governed by the server action gateway:

1. Android asks the server to navigate.
2. If the server returns a pending approval, Android shows the user an approval prompt.
3. Approval is posted to the server.
4. Only then does Android call the approved request execution endpoint.
5. The Android client writes a local receipt; the server also retains its action audit event.

This preserves the same trust boundary whether the browser is local Docker or a future hosted browser service.

## Current first-version boundary

The first Android build implements real app launching and the safe primitives needed for richer device use. It intentionally does not treat Accessibility permission as unrestricted consent, and it does not automatically send messages or perform purchases. Those actions require separate capability definitions and execution paths.
