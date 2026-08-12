# iOS MapKit nearby basemap

## Goal

Show an Apple Maps road basemap underneath the existing Drive nearby-location
canvas on iOS while keeping LapSight's selected `LocationSampleProvider` as the
authoritative source for position, accuracy, course geometry, and timing.

## Scope

1. Add a small common `expect` seam for a platform nearby basemap.
2. Implement the seam with `MKMapView` through Compose `UIKitView` on iOS.
3. Keep Android on the existing offline grid canvas with a no-op `actual`.
4. Reserve stable provider identifiers for Google Maps and AMap; unsupported
   choices safely fall back to the platform default until their SDKs are added.
5. Keep the provider contract in WGS-84; an AMap renderer must handle any
   required GCJ-02 display conversion without changing recorded GPS data.
6. Retain the shared accuracy circle, current-position marker, heading arrow,
   scale, and selected-course overlay above the platform basemap.
7. Reuse the same basemap and geographic projection while marking a new track
   in portrait and landscape, with the captured path drawn above the map.
8. Do not add a Google API key, third-party SDK, geocoding, routing, traffic,
   analytics, or a second location manager.

## Behavior

- Before a valid first fix, keep the existing waiting-for-GPS state.
- After a valid fix on iOS, center a standard Apple map on that fix with the
  existing 500 m nearby viewport.
- If Apple map tiles are unavailable, the existing grid and telemetry overlays
  remain usable.
- Android behavior remains visually and functionally unchanged.

## Verification

- Add common tests for valid/invalid nearby map centers.
- Run shared host tests.
- Compile both iOS simulator and device Kotlin targets.
- Build the iOS app target without staging local signing or derived-data files.
