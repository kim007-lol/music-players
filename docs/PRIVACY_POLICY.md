# Privacy Policy for MusPlay

*Last updated: October 17, 2026*

MusPlay ("we", "our", or "the application") is committed to protecting your privacy. This Privacy Policy describes how MusPlay handles information when you use our mobile application.

---

## 1. Overview
MusPlay is designed as a privacy-friendly audio and music player application. We do not require account registration, do not collect personal identifying information (PII), and do not sell, rent, or monetize your data.

---

## 2. Permissions & Data Usage

### 2.1 Audio Recording (`RECORD_AUDIO`)
- **Purpose**: MusPlay requests access to the microphone / audio recording interface strictly to power the in-app real-time audio visualizer and equalizer processing.
- **Data Handling**: Audio data is processed **ephemerally and exclusively on your device in volatile memory (RAM)**. 
- **No Recording or Transmission**: MusPlay **does not record**, **does not save to disk**, and **does not transmit** any microphone or audio data to external servers or third parties.

### 2.2 Storage & Media Files (`READ_MEDIA_AUDIO` / Storage)
- **Purpose**: To allow you to browse and play audio files stored locally on your device.
- **Data Handling**: Scanned metadata (such as track titles, artists, and album names) is stored exclusively in a local SQLite database on your device. It is never uploaded to any remote server.

### 2.3 Network Communication (`INTERNET`, `ACCESS_NETWORK_STATE`)
- **Purpose**: To stream online audio, download lyrics, fetch album artwork, and support optional features such as Last.fm scrobbling and cloud synchronization.
- **Data Handling**: Network requests are sent only to service providers necessary to retrieve the requested media. We do not track your browsing habits or share your listening history with advertisers.

### 2.4 Bluetooth & Notifications (`BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`)
- **Purpose**: To show playback controls in the system notification drawer, integrate with lock screen media sessions, and auto-detect Bluetooth audio endpoints.
- **Data Handling**: Used solely for local system integration.

---

## 3. Third-Party Services
Depending on the features you choose to use, MusPlay may interact with the following third-party endpoints:
- **Last.fm** (Optional): If you connect your Last.fm account, track scrobbles are submitted to Last.fm according to Last.fm's privacy policy.
- **Lyrics & Metadata Providers**: Used strictly to retrieve lyrics and track details for songs currently playing.
- **No Advertising or Analytics SDKs**: MusPlay does not include third-party advertising SDKs (such as Google AdMob) or invasive telemetry trackers.

---

## 4. Data Retention & Deletion
All application settings, listening statistics, cached playlists, and downloaded files are stored locally on your device. You can permanently delete this data at any time by:
1. Clearing app data and cache via Android **Settings > Apps > MusPlay > Storage > Clear Data**.
2. Uninstalling the application from your device.

---

## 5. Children's Privacy
MusPlay does not knowingly collect or solicit personal information from children under the age of 13. If you believe that a child has provided us with personal information, please contact us so we can take appropriate measures.

---

## 6. Changes to This Privacy Policy
We may update our Privacy Policy from time to time. Any changes will be posted on this page with an updated "Last updated" date.

---

## 7. Contact Us
If you have any questions or suggestions regarding this Privacy Policy, please contact the maintainers via our official repository or issue tracker:
- **GitHub**: https://github.com/kim007-lol/music-players
