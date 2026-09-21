# LightONKaroo v0.1.2-R2 "Janus"

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
- **Janus Split-Field Control**: 
  - **Left Tap**: Toggles between Primary and Secondary ON modes.
  - **Right Tap**: Turns lights OFF (or toggles in classic mode).
  - **Classic Mode**: Standard ON/OFF toggle available via settings.
- **Customizable Modes**: Configure Primary ON, Secondary ON, and OFF states for each light.
- **Custom Light Names**: Rename your lights (e.g., "Helmet Light") directly in the extension settings.
- **Intelligent Status UI**: 
  - Top-left lamp indicator (Authentic Karoo Turquoise when ON, White when OFF).
  - Visual Battery Icons (Full/Half/Empty) with color-coded alerts.
  - Adjustable rotation speed (5 to 30 seconds).
  - **Radar Fallback**: For rear lights, automatically pulls battery data from the linked Radar sensor.
- **Advanced Ride Control**:
  - Auto-on when starting a ride.
  - **Customizable Pause Behavior**: Choose between doing nothing, switching to OFF mode, Primary, Secondary, or truly turning lights OFF.
  - **Auto-Cleanup**: Truly turns off lights after finishing your ride to save power.
- **Efficiency**:
  - **View-Aware**: Rotation only runs when the data field is visible on screen.
  - **Massive Diät**: APK size reduced from 14MB to 3.8MB.

## 🚲 Hardware Compatibility
- **Tested Hardware**: Magene AT1200 (Front), Coospo TR70 (Rear Radar), Raveman FR300 ANT+ (Front), Cycplus L7 (Rear Radar).
- **ANT+**: Works with most smart lights recognized by Karoo.
- **Bluetooth (BLE)**: Support for Magicshine inherited but **completely untested**.

## 📝 Changelog
### v0.1.2-R2 "Janus"
- **NEW**: Customizable "Side-Glow" effect with intensity slider (Stiffness/Punch).
- **NEW**: Option to hide/show the background logo for a minimalist look.
- **UI**: Authentic Karoo colors (Turquoise #32e09a, Yellow #ffe714, Red #d34343).
- **UI**: Improved battery icons (Full for Good, Half for Medium, Empty for Low).
- **FIX**: Adjusted lamp icon position and font size for better small-field fit.
- **FIX**: Resolved Auto-On bug where lights would sometimes start even if disabled.

### v0.1.2
- **NEW**: Split-Field Control (Janus Mode). Left side toggles Primary/Secondary, Right side turns OFF.
- **NEW**: Added "Secondary ON Mode" configuration for every light.
- **NEW**: Customizable "Ride Pause Behavior" (None, OFF, Primary, Secondary, Hard-OFF).
- **NEW**: Smart Resume: Automatically restores the light mode used before the pause.
- **NEW**: Custom nickname selection to fix Karoo's naming limitations.
- **NEW**: Rotation speed slider (5s to 30s steps) in settings.
- **UI**: Authentic Karoo Turquoise (#27D9B4) and high-contrast Yellow status labels.
- **UI**: Dynamic battery icons (Full for Good, Half for Medium, Empty for Low).
- **UI**: Top-left lamp status indicator.
- **FIX**: Resolved "black screen" issues by simplifying the layout.

### v0.1.0-alpha
- Initial major alpha release with View-Aware logic and Radar fallback.

### v0.0.3
- Added configurable OFF-Mode.
- Added status rotation and color-coding.
- **Improved SVG rendering** and centering for custom logo.
- **Intelligent battery fallback**: Rear lights can now pull data from linked Radar.

### v0.0.2
- Initial fork and rebranding.
- Integrated custom logo.
- Simplified manual control logic.

## 📜 License
Licensed under the **MIT License**.

---
*Created with AI assistance - Designed for the ride.*
