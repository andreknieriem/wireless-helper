# Privacy Policy for Wireless Helper (Open Headunit, former Headunit Revived)

**Last updated:** September 1, 2026

This Privacy Policy applies to the mobile application **Wireless Helper (Open Headunit)** (also known as **Wireless Helper**, package name: `com.andrerinas.wirelesshelper`), developed and published by **André Rinas** ("we", "our", or "us").

We are committed to protecting your personal data and your privacy. This policy explains our practices regarding the handling of information in connection with our mobile application.

---

## 1. Developer and Application Identification

*   **Application Name:** Wireless Helper (Open Headunit) / Wireless Helper
*   **Package Name:** `com.andrerinas.wirelesshelper`
*   **Developer / Publisher:** André Rinas
*   **Contact Email:** headunit@andrerinas.com
*   **Official Website:** https://headunit.andrerinas.com

---

## 2. Important Disclosure: Location Data Access

**Wireless Helper (Open Headunit) requires access to your device's location data, including in the background, solely to enable the "Auto-start on Wi-Fi" automation feature.**

*   **Why we need it:** Starting with Android 8.0 (and strictly enforced in Android 10+), the Android operating system requires the `ACCESS_FINE_LOCATION` and `ACCESS_BACKGROUND_LOCATION` permissions to allow any application to read the network name (SSID) or BSSID of the currently connected Wi-Fi network.
*   **How we use it:** The application checks the Wi-Fi network SSID only to determine if your phone has connected to your car's Wi-Fi hotspot or Wi-Fi Direct network, so it can automatically trigger the wireless connection handshake for Android Auto without manual interaction.
*   **No Storage, No Tracking, No Sharing:** We **do not** collect, log, track, store, or transmit your geographical coordinates, GPS position, or location history. All SSID checks occur strictly locally on your device in real-time. Your location data is **never** sent to any external server and is **never** shared with third parties.
*   **User Choice & Control:** Location access is completely optional. If you do not use Wi-Fi auto-start (for example, if you use Bluetooth auto-start or manual connection), you can decline or revoke location permissions at any time in system settings without affecting other connection modes.

---

## 3. Information Collection and Processing

**We do not collect, store, sell, or share any personally identifiable information (PII).**

Wireless Helper is a local connectivity tool designed to act as a bridge and trigger for Android Auto. We do not track user behavior, app usage habits, device identifiers, or personal identities.

---

## 4. Permissions Requested

The application requests the following permissions strictly for technical connectivity:

*   **Location (`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`):** Required by Android exclusively to read the connected Wi-Fi SSID for automated connection triggering.
*   **Bluetooth (`BLUETOOTH`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`):** Used to detect when your phone connects to your vehicle's hands-free Bluetooth system to trigger the Android Auto wireless handshake.
*   **Nearby Devices (`NEARBY_WIFI_DEVICES`):** Used on Android 13+ for Wi-Fi Direct (P2P) and Google Nearby Connections discovery without needing precise location access.
*   **Notifications (`POST_NOTIFICATIONS`):** Used to display a persistent foreground service notification while the helper service is actively running or searching for your head unit.
*   **Foreground Service (`FOREGROUND_SERVICE`):** Keeps the background helper service alive while negotiating or maintaining the connection with your head unit.

---

## 5. Third-Party Services and Analytics

*   **No Analytics:** The app does not include Google Analytics, Firebase Analytics, or any other usage-tracking SDKs.
*   **No Advertising:** The app is completely ad-free and contains no advertising SDKs.
*   **No Third-Party Data Sharing:** No user data or device information is ever shared with, sold to, or monetized by third parties.

---

## 6. Data Security and Retention

Because our application does not collect, transmit, or store personal user data on external servers, there is no risk of remote data breaches or server-side data leaks. All configuration settings (such as chosen Bluetooth MAC addresses or Wi-Fi SSIDs) are stored locally on your device in private app storage (`SharedPreferences`) and are deleted immediately when you uninstall the application or clear app data.

---

## 7. Changes to This Privacy Policy

We may update our Privacy Policy from time to time. Any changes will be reflected by updating the "Last updated" date at the top of this document.

---

## 8. Contact Us

If you have questions, concerns, or requests regarding this Privacy Policy or the data practices of **Wireless Helper (Open Headunit)**, please contact:

*   **Developer:** André Rinas
*   **Email:** headunit@andrerinas.com
*   **Project Repository:** https://github.com/andreknieriem/wireless-helper
