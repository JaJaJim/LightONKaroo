# LightONKaroo v0.1.6 "iGPSport"

**LightONKaroo** is a manual and intelligent bike light controller for the **Hammerhead Karoo 3** (Karoo 2 potentially functional but untested). Its primary purpose is to provide a simple data field to toggle your configured ANT+ and Bluetooth (BLE) lights ON and OFF during your ride, combined with an advanced **Software Threat Mode** that reacts to approaching radar vehicles.

This project is a fork of Dennis Strasser's [KarooFireFly](https://github.com/derstrassi/karoofirefly), created by **Peter Weber** using the **Google Gemini AI Agent**. It incorporates BLE smart light protocol implementations for **iGPSPORT** lights based on Tiago Fontes Dias's [igpsport-karoo](https://github.com/tiagodias00/igpsport-karoo) project.

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
  - **Physical Light Confirmation**: Radar status icon turns Turquoise only when participating threat lights successfully confirm their mode over ANT+ or BLE.
  - **Filtered Threat Verification**: Verifies mode execution strictly for lights assigned to threat mode (`useForThreatMode = true`), ignoring all other lights.
  - **Interactive Radar Simulator**: Offline test loop simulating real FIT vehicle approach events for indoor testing without a physical radar sensor.
  - **Hold Time & Race-Protection**: Configurable hold time (0-5s) with race-condition guards to prevent flickering in dense traffic.
- **Janus Split-Field Control**: 
  - **Left Tap**: Toggles between Primary and Secondary ON modes.
  - **Right Tap**: Turns lights OFF (or toggles in classic mode).
  - **Classic Mode**: Standard ON/OFF toggle available via settings.
- **In-Card Direct Light Configuration**: Configure Primary ON, Secondary ON, OFF state, Display Name, and Threat Warning Mode directly inside expanding light cards without popup dialogs.
- **iGPSPORT Smart Light Integration**:
  - **Dedicated BLE Stack**: Independent Bluetooth controller supporting iGPSPORT smart bike lights (VS1800S, VS1200S, VS800S, VS500) with parsed battery levels, 60s periodic polling, and model-aware custom lighting profiles.
  - **Model-Aware Custom Profiles**: Model-specific controls for **VS1800S** (Dual Beam: High Beam & Low Beam sliders for channels 0 & 1) and **VS1200S / VS800S / VS500** (Single Beam: Brightness slider for MAIN channel 2). Auto-dimming options are suppressed for VS500 (lacks ambient light sensor).
  - **Attribution**: Based on Tiago Fontes Dias's ([tiagodias00](https://github.com/tiagodias00)) [igpsport-karoo](https://github.com/tiagodias00/igpsport-karoo) library under the MIT License.
  - **Testing Note**: Tested with the **VS1800S** model; support for VS1200S, VS800S, and VS500 is implemented speculatively based on `igpsport-karoo` protocol definitions.
- **ANT+ Light Command AIDL Fix**:
  - **Dynamic AIDL Descriptor Discovery**: Implemented dynamic transaction descriptor matching (`LightCommandConnectionAIDL` on TX code 18), resolving `SecurityException: Binder invocation to an incorrect interface` and restoring instant control for all ANT+ bike lights.
  - **Stale Pre-OFF Mode Filtering**: Ignores stale pre-OFF mode callbacks from Karoo OS when a light is set to `OFF`, preventing status sticking on Ravemen FR300 ANT+ and similar lights.
- **BLE Stack & Low-Traffic Optimization**:
  - **Balanced Scan Mode**: Replaced `SCAN_MODE_LOW_LATENCY` with `SCAN_MODE_BALANCED` and added a 10-second auto-timeout to prevent Bluetooth hardware congestion.
  - **Adaptive Reconnection Backoff**: Exponential backoff (3s fast reconnect for 30s $\rightarrow$ 10s $\rightarrow$ 60s background max) to eliminate GATT connection timeouts and Bluetooth stack crashes when lights are powered off.
  - **75ms Inter-Chunk Delay**: Increased chunk delay for multi-part GATT frames to 75ms to match standard BLE connection event intervals.
- **Advanced Light Modes**: Dynamic passthrough of 100% of all ANT+ light modes reported by Karoo OS (including `DAY_FLASH`, `NIGHT_PULSE`, `CUSTOM_MODE_1..8`, etc.) with on-demand parameter refresh `▼`.
- **Intelligent Status UI**: 
  - Top-left status dot: Turns Authentic Karoo Turquoise when all configured lights are online and connected, White when searching or offline.
  - Visual Battery Icons (Full/Half/Empty) with color-coded alerts and integrated battery percentage labels (`Battery Level XX%`).
  - Adjustable rotation speed (5 to 30 seconds).
  - **Radar Fallback**: For rear lights, automatically pulls battery data from the linked Radar sensor.
- **Native Karoo Data Pages Compatibility**: Full support for Karoo Profile Editor gestures (long-press drag & yellow highlight, double-tap edit/delete).
- **Advanced Ride Control**:
  - Auto-on when starting a ride.
  - **Customizable Pause Behavior**: Choose between doing nothing, switching to OFF mode, Primary, Secondary, or truly turning lights OFF.
  - **Auto-Cleanup**: Truly turns off lights after finishing your ride to save power.

## 🚲 Hardware Compatibility
- **Tested Hardware**: Magene AT1200, Magicshine Hori 1300Pro, Ravemen FR300 ANT+, Coospo TR70, Cycplus L7 radar, and iGPSPORT VS1800S.
- **ANT+**: Works with all smart lights recognized by Karoo. Fixed `LightCommandConnectionAIDL` transaction descriptor matching (`TX 18`) to restore instant ANT+ control.
- **Bluetooth (BLE)**: Tested with Magicshine Hori 1300S / 1300Pro and iGPSPORT VS1800S. Implemented speculative support for VS1200S, VS800S, and VS500 series smart lights based on igpsport-karoo.

## 📝 Changelog
### v0.1.6 "iGPSport"
- **iGPSPORT BLE SMART LIGHT INTEGRATION**:
  - **Dedicated BLE Stack**: Independent Bluetooth controller supporting iGPSPORT smart bike lights (VS1800S, VS1200S, VS800S, VS500) with parsed battery levels, periodic 60s battery polling, and model-aware custom light profiles.
  - **Model-Aware Custom Profiles**: Model-specific controls for **VS1800S** (Dual Beam: High Beam & Low Beam sliders for channels 0 & 1) and **VS1200S / VS800S / VS500** (Single Beam: Brightness slider for MAIN channel 2). Auto-dimming options are suppressed for VS500 (lacks ambient light sensor).
  - **Source Attribution & Credits**: Based on Tiago Fontes Dias's ([tiagodias00](https://github.com/tiagodias00)) [igpsport-karoo](https://github.com/tiagodias00/igpsport-karoo) project under the MIT License.
  - **Testing Note**: The iGPSPORT stack was tested with the **VS1800S** model; support for VS1200S, VS800S, and VS500 is implemented speculatively based on `igpsport-karoo` protocol definitions.
- **ANT+ LIGHT COMMAND AIDL FIX**:
  - **Dynamic AIDL Descriptor Discovery**: Implemented dynamic transaction descriptor matching (`LightCommandConnectionAIDL` on TX code 18), resolving `SecurityException: Binder invocation to an incorrect interface` and restoring instant control for all ANT+ bike lights.
  - **Continuous SensorService Binding**: Ensures Karoo `SensorService` remains bound whenever ANT+ lights are assigned, keeping connection states (`CONNECTED`) and light parameters (`actualModes`) streaming in real time.
- **RAVEMEN FR300 ANT+ STALE PRE-OFF FILTER**:
  - **Stale Mode Filtering**: Ignores stale pre-OFF mode callbacks from Karoo OS when a light is set to `OFF`, preventing status sticking on Ravemen FR300 ANT+ and similar lights.
- **BLE STACK & LOW-TRAFFIC OPTIMIZATION**:
  - **Balanced Scan Mode**: Replaced `SCAN_MODE_LOW_LATENCY` with `SCAN_MODE_BALANCED` and added a 10-second auto-timeout to prevent Bluetooth hardware congestion.
  - **Adaptive Reconnection Backoff**: Exponential backoff (3s fast reconnect for 30s $\rightarrow$ 10s $\rightarrow$ 60s background max) to eliminate GATT connection timeouts and Bluetooth stack crashes when lights are powered off.
  - **75ms Inter-Chunk Delay**: Increased chunk delay for multi-part GATT frames to 75ms to match standard BLE connection event intervals.
- **UI IMPROVEMENTS**:
  - **Collapsible Custom Profiles**: Custom Light Profiles section is collapsed by default and expands via a toggle switch.
  - **Clean Battery Level Display**: Removed diagnostic boxes; battery level is cleanly integrated into the light subtitle (`Battery Level XX%`).
  - **Unified Sliders & Compact Buttons**: All slider controls use `StepperSlider` with 30% smaller stepper buttons (`25.dp`).
  - **Pure English UI**: Standardized all UI labels to clean English.

### v0.1.4-R3 "Threat"
- **UI MODERNIZATION**: In-Card expanding light cards with Slide-to-Activate and high-contrast styling.
- **ADVANCED LIGHT MODES**: 100% dynamic passthrough of all ANT+ modes reported by Karoo OS with on-demand refresh.
- **KAROO PROFILE EDITOR COMPATIBILITY**: 100% gesture release during profile editing.

### v0.1.4-R2 "Threat"
- **NEW**: Automatic Update Compatibility: Enhanced versioning (`versionCode 20` and `-R2` release revision format) for seamless Karoo Extension Manager update detection.

### v0.1.4 "Threat"
- **NEW**: Software Threat Mode: Automatic radar-triggered warning lighting for ANT+ radars.
- **NEW**: Interactive Radar Simulator: Offline test loop simulating real FIT vehicle approach events for indoor testing.
- **NEW**: Physical Light Confirmation: Radar status icon verifies live ANT+ mode feedback (Turquoise when confirmed, White when waiting).
- **NEW**: Automatic Ride BLE Discovery & Auto-Connect: Background Bluetooth discovery and auto-reconnection during active rides when the data field is active, supporting Magicshine, Hori, Evo, CBL, and Seemee devices.
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

### v0.1.0-alpha
- Initial major alpha release with View-Aware logic and Radar fallback.

### v0.0.3
- Added configurable OFF-Mode.
- Added status rotation and color-coding.
- Improved SVG rendering and centering for custom logo.
- Intelligent battery fallback: Rear lights can now pull data from linked Radar.

### v0.0.2
- Initial fork and rebranding.
- Integrated custom logo.
- Simplified manual control logic.

## 📜 License & Credits
- Licensed under the **MIT License**.
- **Tiago Fontes Dias** ([tiagodias00](https://github.com/tiagodias00)): Author of [igpsport-karoo](https://github.com/tiagodias00/igpsport-karoo) (MIT License).
- **Dennis Straßer** ([derstrassi](https://github.com/derstrassi)): Author of [KarooFireFly](https://github.com/derstrassi/karoofirefly) (MIT License).
- **Peter Weber** ([JaJaJim](https://github.com/JaJaJim)): Project maintainer of LightONKaroo.

---
*Created with AI assistance - Designed for the ride.*
