# LapSight Track UX — Codex / Antigravity Comparison

**Date:** 2026-07-09  
**Codex sketches:** `001-drive-staging-hierarchy`, `002-track-center-list`  
**Independent review:** `ANTIGRAVITY_TRACK_UX_PROPOSAL.md`

## Decision

Use Codex Sketch 001 Variant A and Sketch 002 Variant A as the structural
baseline. Integrate four Antigravity details:

1. The local map visualizes horizontal-accuracy radius and a distance scale.
2. Track Center list rows include restrained miniature course outlines.
3. Rotation pending state ends only after the actual window orientation changes.
4. A selected track is never silently cleared because GPS drifts or the user
   temporarily leaves the venue.

## Point-by-Point Comparison

| Area | Codex proposal | Antigravity proposal | Decision |
|---|---|---|---|
| Drive hierarchy | GPS metrics, stable Track Center row, restrained local map, conditional controls, state-correct CTA | Track row, larger radar map, GPS block below map, large Start button | Use Codex ordering; it preserves current learned hierarchy and keeps readiness visible without making the map dominant. |
| No-track action | Primary CTA becomes `选择赛道` and opens Track Center | Disabled `START TIMING`; clicking produces a toast | Use Codex. A dead primary action adds friction and asks the user to infer a prerequisite. |
| Local map | A few hundred meters, draggable, no pins, position + accuracy + scale; selected local course segment may overlay | 200–500 m radar, accuracy ring, selected course, course-up auto-fit | Combine. Keep accuracy/scale, but remain north-up and locally centered; do not auto-fit or rotate the whole course because that changes the map's spatial meaning. |
| Main-page metadata | Track name only; detailed provenance/version stays secondary | Name, layout, and version in the Drive track row | Use the quieter Codex row. Version and source belong in Track Center detail unless compatibility requires an explicit warning. |
| Existing-track controls | Direction appears only when relevant | Direction plus topology controls expand on Drive | Direction only. Topology is part of the selected layout and must not be casually changed from Drive. |
| Glasses | Entire control absent until a selected device ID exists | Paired-only pill/controller | Combine the conditional rule with the existing segmented HUD-page control. Do not reduce a multi-state control to a status-only pill. |
| Track Center | Explicit Recent and Nearby sections; fixed bottom `搜索更多赛道`; custom creation in overflow | Top search field, horizontal recent cards, nearby list, fixed bottom custom-track action | Use Codex. Search is a manual fallback and custom recording is rarer still; neither should displace the normal Recent/Nearby path. |
| Track Center performance data | No lap time or telemetry | Recent cards include personal-best lap time | Reject. This violates the agreed boundary that all performance data belongs to Session Review. |
| Search | Separate search page with actual text field and database matching | Search directly at the top of Track Center | Use separate search page. It keeps the default screen task-focused and avoids keyboard/search chrome during normal reuse. |
| Review | Sessions and Raw Captures only | Sessions and Raw Captures only | Agreement. Session detail may show immutable referenced track identity but cannot manage tracks. |
| Rotation | Native request + actual container-size confirmation | Same, plus timeout/haptic failure feedback | Adopt actual confirmation; add explicit failure feedback after timeout where the platform supports it. |
| Theme | Existing semantic light/dark themes | Force premium dark racing theme | Keep semantic System/Dark/Light support. Timing may be strongly dark/high-contrast, but preparation and management screens should respect user theme. |

## Integrated Interaction Contract

### Drive — stationary preparation only

1. GPS quality remains at the top.
2. The Track row remains immediately below and always opens full-screen Track
   Center.
3. The map is always present and never becomes an empty-state billboard.
4. The map has no track pins, search, discovery actions, source labels, or
   full-surface navigation.
5. With no selected track, it shows current position, accuracy radius, scale,
   and eventually a road/satellite basemap.
6. With a selected track, only the locally relevant course geometry is
   overlaid; the map stays locally centered and north-up.
7. Direction appears only for selected layouts that support both directions.
8. Glasses controls render only after the user has selected a glasses device in
   Settings.
9. Primary action is `选择赛道` when none is selected and `开始计时` only when
   track and Ready requirements are met.
10. Starting Timing replaces the complete Drive surface with the existing
    fullscreen Timing interface.

### Track Center — track content only

1. Fullscreen list; no map and no pins.
2. `最近使用` first, capped at three.
3. `附近赛道` second, ordered by physical distance.
4. Each row may show name, distance/last-used reason, direction/layout summary,
   selection state, and a small course outline.
5. No personal best, lap time, delta, telemetry, or session counts.
6. Fixed bottom action opens the dedicated database-search page.
7. Custom-track creation lives in the overflow menu and search-no-result
   recovery path.
8. Selecting a track is explicit and persisted; GPS movement never silently
   changes or clears it.

### Review — performance only

1. Top-level groups are Sessions and Raw Captures.
2. Track management is removed.
3. Session detail can display the immutable track/layout/direction/timing-line
   version referenced by that session.
4. Lap time, sectors, Ghost/delta, telemetry, exports, and comparisons remain
   entirely under Session Review.

## Rejected Patterns

- Track pins or track discovery on the Drive map.
- Making the entire Drive map a Track Center button.
- A disabled Start button as the only no-track guidance.
- Search as the dominant Track Center element.
- Personal-best timing data in Track Center.
- Topology editing on Drive for an existing standard layout.
- A permanent disabled glasses selector for users without glasses.
- Fixed-duration fake rotation feedback.
- Automatic selected-track changes based on current GPS position.
- Forcing a single dark theme across preparation and management surfaces.

## Implementation Order

1. Fix paired-only glasses visibility and add native iOS orientation handling.
2. Refactor Drive into the selected Variant A hierarchy and remove the map empty
   state.
3. Add full-screen Track Center with local Recent/Nearby repository data.
4. Remove Track management from Review and route it to Track Center.
5. Add the dedicated search page and a catalog-provider boundary.
6. Add road/satellite basemap and public catalog synchronization without
   changing the established screen hierarchy.
