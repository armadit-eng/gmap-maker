# GMAP Maker (Android)

Native Android (Kotlin + Jetpack Compose) application for building offline `.gmap` (`GMP1` / `GNM1` / `GRT1`) map packages from OpenStreetMap data for GESITS Map Unit.

## Features
- **Radius & Navigation Modes**: Generate map packages from a center coordinate/radius or along an OSRM driving route corridor (with optional toll avoidance and 2/3/5 km corridor width).
- **Interactive Point-Picker Map**: Pan/zoom slippy tile map supporting OpenStreetMap, OSM Humanitarian (HOT), and Carto Voyager (with saved API key / URL template via DataStore).
- **Device GPS & Geocoding**: Acquire device coordinates via `LocationManagerCompat` or geocode place names in Indonesia via Nominatim.
- **Multi-Endpoint Overpass Extraction**: Automatic endpoint rotation and recursive quadrant block splitting with adaptive Douglas-Peucker simplification and polygon clipping.
- **5 Map Styles & Preview**: Live Compose Canvas preview of the compiled `.gmap` file across all 5 styles (`Standar`, `Malam`, `Kontras siang`, `Alam / offroad`, `Carto Voyager`).
- **Export & ESP32 WiFi Upload**: Save `map.gmap` via Storage Access Framework or upload directly to `http://192.168.4.1/upload` on `GESITS-MAP` WiFi.
- **Release APK CI**: GitHub Actions workflow (`.github/workflows/build-release-apk.yml`) builds, tests, optionally signs, and publishes release APKs.
