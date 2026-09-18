# LightONKaroo

**LightONKaroo** is a manual bike light controller for the Hammerhead Karoo 3. (Karoo 2 potentially functional but untested). It allows you to toggle your configured ANT+ and Bluetooth (BLE) lights directly from a data field during your ride, with customizable modes for both ON and OFF states.

This project is a fork of Dennis Strasser's [KarooFireFly](https://github.com/derstrassi/karoofirefly), created by **Peter Weber**. It was realized using the **Google Gemini AI Agent** within Android Studio without manual programming (and GIT ;) ) knowledge. The new logo was also custom designed and integrated.

## Features
- **One-Tap Toggle**: Turn all configured lights to their predefined ON or OFF modes by tapping a data field.
- **Customizable Modes**: Configure separate light modes for the "ON" and "OFF" state of each light (e.g., Steady High for ON, Slow Flash for OFF).
- **Status Rotation**: Optional data field rotation showing the actual device name, connection status, and battery level for all lights.
- **Intelligent Battery Monitoring**: 
  - Visual battery level indicator with color-coded alerts (Green, Yellow, Orange, Red).
  - **Radar Fallback**: For rear lights that don't report battery levels via the light profile, the app automatically pulls battery data from the linked Radar sensor.
- **Intelligent Ride Control**:
  - Auto-on when starting a ride.
  - Optional hard-OFF when finishing a ride to save battery.
  - Optional safety mode switch when pausing a ride (configured OFF-mode).
- **Manual Focus**: All automatic sensors from the original project were removed for a predictable, manual experience.

## Hardware Tested
- **Magene AT1200** (Front Light): Functional.
- **Coospo TR70** (Rear Radar Light): Functional.
- **Raveman FR300 ANT+** (Front Day Light): Light control functional; however, it may report incorrect status/battery (possibly due to incomplete ANT+ implementation).
- **Cycplus L7** (Rear Radar Light): Light control functional; occasionally buggy during initial activation/deactivation.

## Changelog
### v0.0.3
- **NEW**: Configurable OFF-Mode (e.g., use blinking instead of turning completely off).
- **NEW**: Detailed status rotation in the data field (Name, Status, Battery %).
- **NEW**: Intelligent battery fallback: Rear lights can now pull battery data from their Radar counterpart.
- **NEW**: Color-coded battery levels in the data field.
- **NEW**: Option to toggle detailed status rotation in settings.
- **IMPROVED**: Refined UI with Logo as background in the data field.
- **IMPROVED**: Logic for Auto-OFF on finish vs. pause (Hard-OFF vs. Configured Mode).
- **CLEANUP**: Removed all unused auto-sensor code and legacy controllers.
- **FIX**: Improved SVG rendering and centering for all data field sizes.

### v0.0.2
- Initial fork and rebranding.
- Integrated new custom logo.
- Simplified manual control logic.

## Important Warning
> [!WARNING]
> **Early Development / Use at Your Own Risk**
> This extension uses an undocumented internal Karoo API that may break with future firmware updates. The author is not responsible for any issues or failures during use.

## License
This project is licensed under the **MIT License**, following the original license of the KarooFireFly project.

---
*Created by Peter Weber with AI assistance.*
