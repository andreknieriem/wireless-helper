<p align="center">
  <img src="app/src/main/res/ic_launcher-playstore.png" width="200" alt="Wireless Helper Logo">
</p>

# Wireless Helper

<a href='https://play.google.com/store/apps/details?id=com.andrerinas.wirelesshelper'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' width="200"/></a>

A lightweight launcher utility for **Headunit Revived**.

This app acts as a trigger to start Android Auto Wireless on your phone. It automatically detects your tablet running Headunit Revived via NSD (mDNS) or a passive TCP trigger, ensuring a seamless wireless connection experience.

## Features
- **Auto-Trigger:** Fires the Android Auto wireless intent as soon as the Headunit is detected.
- **NSD Support:** Finds your tablet in the same WiFi network automatically.
- **Passive Mode:** Waits for a trigger from the Headunit (ideal for Tablet-Hotspot setups).
- **Zero-Config:** No manual IP entry required.

## Known Issues
- WiFi-Triggers on Android 10 (Q) and below: Google has disabled the automatic wireless projection startup for Android 10 and below in Android Auto versions 16.4 and higher. While it still work on newer Android versions, it is currently impossible to trigger projection on Android 10 with recent Google app updates.

## Auto-Start Setup
To ensure reliable background auto-start (Bluetooth/WiFi) and a stable connection:
1. **Disable Battery Optimization (CRITICAL):** Go to App Info -> Battery -> select **"Unrestricted"**. Since this app acts as a data proxy for Android Auto, it must not be put to sleep while driving.
2. **Permissions:** Grant Bluetooth and Location permissions when prompted.
3. **Paired Devices:** Select your car's Bluetooth device in the settings.

## Automation (Tasker / MacroDroid / ADB)
Wireless Helper supports remote control via Android Intents and App Shortcuts.

### URI Schemes
- **Start Search:** `wirelesshelper://start`
- **Start with Specific Mode:** `wirelesshelper://start?mode=<MODE_ID>`
- **Stop Search:** `wirelesshelper://stop`

### Supported Modes
| Mode ID          | Description        |
|:-----------------|:-------------------|
| `nsd`            | Shared Wi-Fi       |
| `phone-hotspot`  | Phone Hotspot mode |
| `tablet-hotspot` | Tablet Hotspot     |
| `wifi-direct`    | Wi-Fi Direct       |
| `nearby`         | Google Nearby      |

### ADB Examples
```bash
# Start searching in Phone Hotspot mode
adb shell am start -a android.intent.action.VIEW -d "wirelesshelper://start?mode=phone-hotspot"

# Stop searching
adb shell am start -a android.intent.action.VIEW -d "wirelesshelper://stop"
```

## Changelog
### v.1.8.1
- Fixed QR Crash
- Enhanced Google Nearby Manager
- Added Autostart via BT delay

### v.1.8.0
- Added: Auto-Connect to Tablet Hotmode mode. Thanks to @BackSlasher
- Added: QR Code scanner for easy connection in Tablet Hotspot Mode
- Fixed czech translation. Thanks to @teodortomas

### v1.7.0
- Enhanced: Bluetooth list now shows warning if BT is turned off
- Enhanced: If Wifi is off, the helper now waits for Wifi to be turned on
- Added: Bluetooth Aliases are now shown when set

### v1.6.4
- Added korean 🇰🇷 translation, thanks to kendrickkim
- Fixed: Bug in Bluetooth list

### v1.6.3
- Added: Click on Notification brings you to the App

### v1.6.2
- Bugfix Release: Fixed wrong handling in searching. It is now fast again
- Added: Force Hotspot disable (in combination with bluetooth disconnects)

### v.1.6.1
- Added turkish 🇹🇷 translation, thanks to @ferhat4555

### v.1.6.0
- Added Italian 🇮🇹 translation
- Added Google Nearby Feature. This Feature needed Headunit Revived 2.2.0 or later
- Added static ip text field for Hotspot on Tablet Mode to set up a static ip

### v.1.5.2
- Fixed: Don't disable Hotspot if already running. thanks to @dhanar10

### v.1.5.1 Bugfix Release
- Merging several Merge Request with fixes for broken 1.5.0. Thanks to @dhanar10 and @mixalbl4-127

### v.1.5.0
- Enhanced: Wi-Fi Direct now scans faster to find the headunit
- Added: Option to auto-start the service on app-open

### v.1.4.1
- Added: Multiple Wi-Fi Direct Names now possible
- Enhanced: Auto-Enable Hotspot. As before. This will not work on very new devices, but should work until Android 13

### v.1.4.0
- Added: Multiple Selection for Bluetooth Auto-Start Devices
- Added: Multiple SSID's name for Wi-Fi Auto-Start Devices
- Added: Try to auto enable Phone Hotspot. This will only work for older devices as Android restricted this on newer Android versions!
- Added: Log-Export for debugging like in HeadUnit Revived
- Enhancement: Try to save battery life with smarter code
- Fixed: Better start for new Android Auto versions. Now tries all methods

### v.1.3.1
- Added: Spanish translation 🇪🇸 thanks to @tsabaia
- Fixed: Bug with Wi-Fi Popup on Hotspot mode

### v.1.3.0
- Added: Try to auto reconnect when bluetooth device still connected
- Added: Ignore bluetooth disconnection and keep service running
- Added: Byebye intent to Headunit for cleaner closes

### v.1.2.0
- Fixing start of Android Auto with new Version 16.4. The intent is gone and now a broadcast.
- Fixing Fatal Crash on Wifi-Direct closing

### v.1.1.0
- Enhancement for the Wifi-Direct Mode. Note: Since Android 10 this mode is very restrictive and it might now work for every modern phone. Just try it

### v.1.0.1
- Fixing offline mode

### v.1.0.0 - First ready version for Playstore!
- Added Deep Links and App Shortcuts for full automation (Samsung Modes & Routines support).
- Added Arabic translation, thanks to @A5H0
- Improved background activity launch and service lifecycle.

### v.0.5.0
- Added 1x1 Widget for Launcher
- Added Quick-Settings-Tile
- Changed Logo because of the new icons
- Merged new translations

### v.0.4.0
- Added experimental Auto-Start on Wi-Fi logic
- Fixed connection hangs and improved discovery logic.
- Notification now disappears correctly when the session ends or is closed from the headunit.
- Added support for system navigation bar insets (no more cut-off content).
- Added translation support, thanks to @saksonovdev with english, russian and german language

### v.0.3.0
- Complete Rebuild of the functions. Now using a proxy server to establish the connections
- Changed the UI a little bit

### v.0.2.0
- Added Auto Scan on Bluetooth Connection

### v.0.1.0
- First release