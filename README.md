# LightONKaroo v0.1.0-alpha

**LightONKaroo** is a manual bike light controller for the **Hammerhead Karoo 3**. (Karoo 2 potentially functional but untested). Its primary purpose is to provide a simple data field to toggle your configured ANT+ and Bluetooth (BLE) lights ON and OFF during your ride.

This project is a fork of Dennis Strasser's [KarooFireFly](https://github.com/derstrassi/karoofirefly), created by **Peter Weber** using the **Google Gemini AI Agent**. It’s an "AI-assisted experiment" designed for simplicity and direct control.

## ⚠️ Important Warning
> [!WARNING]
> This extension uses an undocumented internal Karoo API. Use at your own risk. Future firmware updates may impact functionality.

## 🛠 Required Setup (Crucial!)
To allow this extension to control your lights, you **must** disable the Karoo's native "Auto Control":
1. Go to `Settings` > `Sensors` on your Karoo.
2. Select your light sensor.
3. Find the **"Auto Control"** setting and set it to **OFF**.
*If this is not done, the Karoo firmware and this extension will fight for control of the light.*

## 🚀 Features
- **One-Tap Toggle**: The main purpose! Turn all configured lights to their predefined modes by tapping the data field.
- **Customizable Modes**: Configure separate modes for "ON" and "OFF" states (e.g., Steady High for ON, Slow Flash for OFF).
- **Status Rotation**: Optional data field rotation showing the actual device name, connection status, and battery level for all lights. Info cycles every 5 seconds.
- **Intelligent Battery Monitoring**:
  - **3-Stage Logic**: Clear "Good", "Medium", and "Low" labels with color coding.
  - **Radar Fallback**: For rear lights, the app automatically pulls battery data from the linked Radar sensor if the light profile doesn't provide it.
- **Ride Efficiency**:
  - **View-Aware**: Status rotation is only active when the data field is visible on screen to save battery.
  - **Optional Cleanup**: If configured ("Turn off lights after finishing ride"), truly turns off lights after your ride to save power.
  - **Pause Safety**: Option to switch to a safety mode (configured OFF-mode) instead of complete shutdown when pausing.

## 🚲 Hardware Tested
- **Tested Hardware**: Magene AT1200 (Front), Coospo TR70 (Rear Radar), Raveman FR300 ANT+ (Front), Cycplus L7 (Rear Radar).
- **ANT+**: Should work with most smart bike lights (Garmin Varia, Bontrager Ion, Flare, etc.) as long as the Karoo recognizes them as light sensors. 
- **Bluetooth (BLE)**: Support for Magicshine (M1/M2/M3) is inherited from the original project but is **currently completely untested**.

## 📝 Changelog
### v0.1.0-alpha
- **UI**: Implemented a centered 4-line layout in the data field (Global Status, Device Name, Device Mode, Battery Level).
- **FIX**: Adjusted info cycling to prevent UI overlaps in small data fields.
- **OPTIMIZATION**: Implemented "View-Aware" rotation to reduce CPU impact.
- **IMPROVEMENT**: Simplified battery display (Good/Medium/Low) for better readability.
- **IMPROVEMENT**: Added "Radar Fallback" for rear light battery monitoring.
- **UI**: Added Logo background in the data field with high-contrast text.
- **CLEANUP**: Removed all unneeded auto-sensor code.

### v0.0.3
- Added configurable OFF-Mode (e.g., use blinking instead of true power-off).
- Added status rotation (Name, Status, Battery %) and color-coding.
- Improved SVG rendering and centering.

### v0.0.2
- Initial fork and rebranding.
- Integrated custom logo.
- Simplified logic to manual control only.

## 📜 License
Licensed under the **MIT License**.

---
*Created with AI assistance - Designed for the ride.*
