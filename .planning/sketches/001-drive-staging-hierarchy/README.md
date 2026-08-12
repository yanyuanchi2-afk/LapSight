---
sketch: 001
name: drive-staging-hierarchy
question: "How should status, track entry, local map, conditional glasses, and the primary action compose before timing?"
winner: "A"
tags: [drive, layout, map, glasses]
---

# Sketch 001: Drive Staging Hierarchy

## Design Question

How should the stationary pre-timing Drive page remain visually stable and
low-friction across no-track, selected-track, and paired-glasses states?

## How to View

```bash
open .planning/sketches/001-drive-staging-hierarchy/index.html
```

## Variants

- **A: Balanced Stack** — status first, stable track-center row, restrained map, progressive controls.
- **B: Map Forward** — track context and map receive more height; status becomes a compact ribbon.
- **C: Precision Cockpit** — selected track and readiness lead; the map becomes a compact verification instrument.

## Winner

**A: Balanced Stack.** It preserves the established GPS-first hierarchy, keeps
the Track Center entry stable, prevents the local map from dominating the page,
and leaves enough room for paired-only glasses controls without rearranging the
primary action.

## What to Look For

- Whether the map feels useful without becoming a discovery surface.
- Whether the track-center entry remains obvious without duplicating an empty state.
- Whether hiding unpaired glasses improves rhythm without producing awkward gaps.
- Whether the no-track primary action tells the user what to do next.
- Whether entering Timing clearly replaces the entire Drive page.
