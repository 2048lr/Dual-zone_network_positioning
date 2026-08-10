# HamKit

An all-in-one console for amateur radio enthusiasts — zone positioning, satellite pass prediction, CW Morse training, transit alerts, and more.

## Roadmap

### Completed

- **CW Trainer** (Morse code learning)
- **Satellite Positioning & Tracking**
- **AMSAT Satellite Status**
- **Calendar Transit Alerts**

### Short-term

- **FT8** refinement (referencing [FT8CN](https://github.com/BG7HIM/FT8CN))
- **APRS** refinement (referencing [aprsdroid](https://github.com/ge0rg/aprsdroid))
- **Satellite module rewrite** (referencing [look4sat](https://github.com/rt-bishop/Look4Sat))

### Long-term

- **RTTY** development (signal processing from scratch)

### Future Plans

- QSO logging
- QSO export (XML)
- Radio repeater lookup (in cooperation with authorized parties)

## Core Features

### Zone Positioning
- One-tap GPS coordinate acquisition
- Real-time **CQ Zone**, **ITU Zone**, and 6-character **Maidenhead Grid** calculation
- Reverse geocoded address display (3-second debounce, exponential backoff on failure)

### Satellite Pass Prediction
- **SGP4/SDP4** orbital calculation via predict4java, parallel 48-hour pass prediction
- Supports **CelesTrak** TLE data sources (amateur / satnogs groups)
- Real-time **AMSAT** status query (with continuation markers), BJT segmented timeline, in-pass countdown
- Favorite satellite support, sorted by favorites → in-pass → AOS

### CW Morse Trainer
- Complete **Koch** curriculum (26 lessons) + character groups / callsign / text training
- Real-time sine wave synthesis via AudioTrack, adjustable WPM, tone, and playback mode
- Training progress tracking

### Transit Alerts
- AlarmManager exact alarms + WorkManager daily refresh
- Configurable lead-time reminders before AOS, daylight-only mode
- Auto-restore on reboot (BootReceiver)

## Screenshots

| Positioning | FT8 | APRS | CW Trainer |
| --- | --- | --- | --- |
| ![Positioning](images/定位页面.jpg) | ![FT8](images/FT8.jpg) | ![APRS](images/APRS.jpg) | ![CW Trainer](images/CW-教程练习.jpg) |

## Tech Stack

**Language & Framework**
- Kotlin 2.4.0
- Jetpack Compose (BOM 2026.05.01)
- Material 3 Expressive + Miuix KMP 0.9.3
- Coroutines 1.11.0

**Data & Location**
- Room 2.7.0 / DataStore 1.1.4
- Google Play Services Location 21.3.0
- OkHttp 5.3.2 / WorkManager 2.10.0

**Domain-specific**
- predict4java 1.3.1 (satellite orbit prediction)
- Amap 3D SDK
- MPAndroidChart v3.1.0
- Coil Compose 2.7.0 / Palette 1.0.0

**Engineering**
- Gradle 9.4.1 / KSP 2.3.10
- JaCoCo 0.8.12 / R8 ProGuard
- GitHub Actions CI/CD

## System Requirements

- Android 8.0 (API 26) and above
- targetSdk 37
- Location permission required

## Feedback

Please report bugs via [Issues](https://github.com/fuxue-linkong/Dual-zone_network_positioning/issues) or email fuxuelingkong@outlook.com.

Feature requests are also welcome (though feasibility is not guaranteed).

## License

[MIT License](LICENSE)

## Disclaimer

I am not a licensed HAM operator — just someone with a casual interest in amateur radio. This project was inspired by fellow enthusiasts around me. It has also been a great opportunity to learn the ropes of Android development.
