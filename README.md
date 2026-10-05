# LightONKaroo v0.1.4-R3 "mini"

**LightONKaroo** is a manual and intelligent bike light controller for the **Hammerhead Karoo 3**. (Karoo 2 potentially functional but untested). Its primary purpose is to provide a simple data field to toggle your configured ANT+ and Bluetooth (BLE) lights ON and OFF during your ride, combined with an advanced **Software Threat Mode** that reacts to approaching radar vehicles.

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
- **Software Threat Mode**:
  - **Radar-Triggered Warning**: Automatically activates assigned warning lighting (e.g. Fast Flash) when compatible ANT+ bike radars detect approaching vehicles.
  - **Physical Light Confirmation**: Radar status icon turns Turquoise only when participating threat lights successfully confirm their mode over ANT+.
  - **Interactive Radar Simulator**: Offline test loop simulating real FIT vehicle approach events for indoor testing without a physical radar sensor.
  - **Hold Time & Race-Protection**: Configurable hold time (0-5s) with race-condition guards to prevent flickering in dense traffic.
- **Janus Split-Field Control**: 
  - **Left Tap**: Toggles between Primary and Secondary ON modes.
  - **Right Tap**: Turns lights OFF (or toggles in classic mode).
  - **Classic Mode**: Standard ON/OFF toggle available via settings.
- **In-Card Direct Light Configuration**: Configure Primary ON, Secondary ON, OFF state, Display Name, and Threat Warning Mode directly inside expanding light cards without popup dialogs.
- **Advanced Light Modes**: Dynamic passthrough of 100% of all ANT+ light modes reported by Karoo OS (including `DAY_FLASH`, `NIGHT_PULSE`, `CUSTOM_MODE_1..8`, etc.) with on-demand parameter refresh `▼`.
- **Intelligent Status UI**: 
  - Top-left status dot: Turns Authentic Karoo Turquoise when all configured lights are online and connected, White when searching or offline.
  - Visual Battery Icons (Full/Half/Empty) with color-coded alerts.
  - Adjustable rotation speed (5 to 30 seconds).
  - **Radar Fallback**: For rear lights, automatically pulls battery data from the linked Radar sensor.
- **Native Karoo Data Pages Compatibility**: Full support for Karoo Profile Editor gestures (long-press drag & yellow highlight, double-tap edit/delete).
- **Advanced Ride Control**:
  - Auto-on when starting a ride.
  - **Customizable Pause Behavior**: Choose between doing nothing, switching to OFF mode, Primary, Secondary, or truly turning lights OFF.
  - **Auto-Cleanup**: Truly turns off lights after finishing your ride to save power.
- **Performance & Efficiency**:
  - **Sequential Command Queue**: Enforces a 250ms inter-command gap and duplicate suppression to prevent ANT+ radio packet collisions and Karoo binder overloads.
  - **150ms Smooth Filter**: Prevents light flickering caused by brief sensor noise or roadside reflections.
  - **Smart Diffing**: Skips redundant UI data field updates when status values remain stable.
  - **View-Aware**: Rotation only runs when the data field is visible on screen.

## 🚲 Hardware Compatibility
- **Tested Hardware**: Magene AT1200, Magicshine Hori 1300Pro, Ravemen FR300 ANT+, Coospo TR70, and Cycplus L7 radar.
- **ANT+**: Works with most smart lights recognized by Karoo.
- **Bluetooth (BLE)**: Tested with Magicshine Hori 1300S and 1300Pro. Partially supported (no high/low beam switching capability).

## 📝 Changelog
### v0.1.4-R3 "mini"
- **UI MODERNIZATION**:
  - **In-Card Direct Light Configuration**: Removed popup dialogs. All light options (Display Name, Primary ON, Secondary ON, OFF Mode, and Software Threat Mode with test `▶` buttons) expand directly inside the light cards.
  - **Stable List Layout**: Light cards retain their exact position in the list when toggling active state ("in-place expansion").
  - **Enhanced High-Contrast Styling**: Clean 25% Karoo Turquoise background tint for the main container and active light cards, 25% Karoo Red tint for disabled/inactive light cards.
  - **High-Contrast Notice & Legend Boxes**: Styled notice box for Karoo Sensor setup in 25% Turquoise, Software Threat Mode disclaimer in 25% Red with black text, and Radar Status legend in 25% Turquoise.
- **ADVANCED LIGHT MODES**:
  - **Dynamic Mode Passthrough**: 100% of all ANT+ modes reported by Karoo OS (including `DAY_FLASH`, `NIGHT_PULSE`, `CUSTOM_MODE_1..8`, etc.) are dynamically passed through directly into the dropdown list.
  - **Expanded Mode Aliases**: Extended translation table mapping manufacturer-specific ANT+ mode strings to clean human-readable labels out of the box.
  - **On-Demand Parameter Refresh**: Tapping the dropdown arrow `▼` on any mode row triggers an immediate AIDL refresh (`forceRefreshLightParameters`), retrieving live parameters from Karoo OS without background CPU/battery overhead.
- **KAROO PROFILE EDITOR COMPATIBILITY**:
  - **Gesture Release in Profile Editor**: Click listeners (`setOnClickPendingIntent`) are now attached strictly during active ride recording (`isRideActive == true`). During Karoo Data Pages profile editing (`rideActive == false`), no click listeners intercept touch events, giving 100% native Karoo gesture compatibility (long-press drag & yellow highlight, double-tap edit/delete).
- **HARDWARE FIX**:
  - **M1_HARD_OFF for Magicshine / Hori 1300Pro**: Resolved 5% standby glow on M1 BLE devices by sending the channel-0 hardware power-off command (`M1_HARD_OFF`), completely powering down the LED driver when set to OFF.

### v0.1.4-R2 "Threat"
- **NEW**: **Automatic Update Compatibility**: Enhanced versioning (`versionCode 20` and `-R2` release revision format) for seamless Karoo Extension Manager update detection.

### v0.1.4 "Threat"
- **NEW**: **Software Threat Mode**: Automatic radar-triggered warning lighting for ANT+ radars.
- **NEW**: **Interactive Radar Simulator**: Offline test loop simulating real FIT vehicle approach events for indoor testing.
- **NEW**: **Physical Light Confirmation**: Radar status icon verifies live ANT+ mode feedback (Turquoise when confirmed, White when waiting).
- **NEW**: **Automatic Ride BLE Discovery & Auto-Connect**: Background Bluetooth discovery and auto-reconnection during active rides when the data field is active, supporting Magicshine, Hori, Evo, CBL, and Seemee devices.
- **PERFORMANCE**: Sequential Command Queue with 250ms spacing and 150ms debounce guard to eliminate ANT+ packet collisions and binder load.
- **PERFORMANCE**: Smart Diffing for zero CPU overhead during stable state rotations.
- **FIX**: Guaranteed 1-second unbind delay ensuring reliable "Truly turn OFF" behavior on ride end.

### v0.1.2-R2 "Janus"
- **NEW**: Customizable "Side-Glow" effect with intensity slider (Stiffness/Punch).
- **NEW**: Option to hide/show the background logo for a minimalist look.
- **UI**: Authentic Karoo colors (Turquoise #32e09a, Yellow #ffe714, Red #d34343).
- **UI**: Improved battery icons (Full for Good, Half for Medium, Empty for Low).
- **FIX**: Adjusted lamp icon position and font size for better small-field fit.

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

### v0.0.3
- Added configurable OFF-Mode.
- Added status rotation and color-coding.
- **Improved SVG rendering** and centering for custom logo.
- **Intelligent battery fallback**: Rear lights can now pull data from linked Radar.

## 📜 License
Licensed under the **MIT License**.

---
*Created with AI assistance - Designed for the ride.*
