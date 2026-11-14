# WaLite Android Browser (APK Project — No PC Required via GitHub Actions)

1. Push these files to your GitHub repository (`main` branch).
2. Open the **Actions** tab on GitHub and wait ~2 minutes for **Build WaLite Android APK** to finish.
3. Download **`WaLite-WhatsApp-Browser-APK.zip`** directly on your Android phone and install `app-debug.apk`.

## Chromium-Style Features
- **WhatsApp + Telegram Only (No Editable URL Bar)**: `←`, `↻`, `WA` (`https://web.whatsapp.com`), and `TG` (`https://web.telegram.org`) switchers while blocking all other domains.
- **Native Hardware Microphone, Camera & Headphones/Bluetooth**: Grants Android runtime Mic/Camera/Bluetooth permissions, lists detected audio input/output devices, routes audio to Headphones or Speaker, and plays a headphone test chime.
- **Chrome Extension `.zip` Support + Disabled Popup**: Tap `+Zip` to extract any Chrome extension `.zip`, inject `content_scripts` (`js` & `css`), serve assets via `https://walite-ext.local/` (`chrome.runtime.getURL`), persist `chrome.storage.local`, and toggle **Disabled Popup** from the pinned extension name button.
