# core-globe

Interactive 3D globe for the web — markers, arcs, `flyTo`, and cinematic camera flyovers, built on [Three.js](https://threejs.org). This is the same renderer as the [`core-globe` Android/KMM library](https://github.com/advait8/core-globe) (`io.github.advait8:core-globe`), ported and typed for framework-agnostic web use.

## Quickstart (CDN, no bundler)

```html
<script src="https://cdn.jsdelivr.net/npm/three@0.170.0/build/three.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/core-globe/dist/core-globe.umd.js"></script>
<div id="globe" style="width:600px;height:600px"></div>
<script>
  const globe = new CoreGlobe.Globe({
    container: document.getElementById('globe'),
    dataBaseUrl: 'https://cdn.jsdelivr.net/npm/core-globe/dist/data/',
    onMarkerClick: (id) => console.log('clicked:', id),
  });
  globe.addMarker({ id: 'sfo', lat: 37.77, lng: -122.41, style: 'current' });
  globe.addMarker({ id: 'tyo', lat: 35.68, lng: 139.69, style: 'destination' });
  globe.addArc({ id: 'sfo-tyo', from: { lat: 37.77, lng: -122.41 }, to: { lat: 35.68, lng: 139.69 } });
  globe.animateArc('sfo-tyo');
</script>
```

`three` is a peer dependency — bring your own copy (r150+). This keeps the core bundle small (~8KB gzipped); land/border GeoJSON is fetched separately at runtime rather than bundled (see `dataBaseUrl` below).

## Install (bundler)

```bash
npm install core-globe three
```

```ts
import { Globe } from 'core-globe';

const globe = new Globe({ container: document.getElementById('globe')! });
```

Your bundler serves `dist/data/*.geojson` alongside your app, or point `dataBaseUrl` at wherever you host them (e.g. copy them into your `public/` folder and use the default `'./data/'`).

## API

### `new Globe(options: GlobeOptions)`

| Option | Type | Description |
|---|---|---|
| `container` | `HTMLElement` | required — the globe fills this element and resizes with it |
| `config` | `GlobeConfig` | colors, toggles, rotation speed — see below |
| `dataBaseUrl` | `string` | base URL for fetching `land.geojson`/`countries.geojson`, default `'./data/'` |
| `onMarkerClick` | `(markerId: string) => void` | fires when a marker dot is tapped/clicked |
| `onCountryClick` | `(iso2: string) => void` | **not yet implemented** — border lines aren't tagged with ISO2 or hit-testable today; typed for a future release, never fires |
| `onArcAnimationComplete` | `(arcId: string) => void` | fires when an `animateArc()` draw-in finishes |
| `onFlightComplete` | `() => void` | fires when `animateFlight()` settles over the destination |
| `onReady` | `() => void` | fires once the renderer is initialized |

### Markers — `addMarker`, `removeMarker`, `clearMarkers`

```ts
globe.addMarker({ id: 'sfo', lat: 37.77, lng: -122.41, style: 'current', label: 'San Francisco' });
```

`style`: `'default'` | `'current'` (pulsing beacon) | `'destination'` (amber dot) | `'visited'` (currently renders identically to `'default'` — no distinct look yet) | `{ color, size?, pulse? }` (custom — note: `pulse` is currently a no-op for custom markers, same gap as the Kotlin source).

### Arcs — `addArc`, `animateArc`, `removeArc`

```ts
globe.addArc({ id: 'sfo-tyo', from: { lat: 37.77, lng: -122.41 }, to: { lat: 35.68, lng: 139.69 }, style: 'flight' });
await globe.animateArc('sfo-tyo'); // draws in over ~1.5s
```

`style`: `'flight'` (draws in over ~1.5s) | `'dashed'` (fully drawn, low opacity — potential destinations) | `'trail'` (fully drawn, dim — past legs) | `{ color?, width? }` (custom).

### Camera — `flyTo`, `animateFlight`

```ts
await globe.flyTo({ lat: 35.68, lng: 139.69 }); // rotate the globe toward a target

// Cinematic flyover: the camera itself sweeps above the surface along the great-circle
// path and descends to a low hover over the destination.
await globe.animateFlight({ lat: 35.68, lng: 139.69 }, { lat: 37.77, lng: -122.41 });
```

### Config — `updateConfig`, `setAutoRotate`

```ts
globe.updateConfig({ autoRotate: false, showBorders: false });
```

Colors baked into already-created markers/arcs/land/borders (`currentDotColor`, `arcColor`, `landColor`, `borderColor`, etc.) only apply to objects created *after* the change — this matches the source renderer, which has no live-recolor path at all. `backgroundColor`, `globeColor`, `atmosphereColor`, `gridColor`, and the `show*` toggles apply live.

### Lifecycle — `resize`, `destroy`

`resize()` is only needed if you're not relying on the built-in `ResizeObserver` (e.g. you changed the container's size without a layout reflow the observer would catch). `destroy()` tears down the WebGL context and animation loop.

## Known gaps (parity with the Android library)

- `onCountryClick` has no working implementation yet.
- `MarkerStyle.Visited` and custom-marker `pulse` don't render distinctly from their non-pulsing/default counterparts.
- `GlobeConfig.tiltRadians` is accepted but has no effect (tilt is hardcoded to 45°).
- `buildGrid()`'s longitude lines converge at the poles, same as any lat/lon grid on a sphere — expected, not a bug, but worth knowing if a pole ever fills the frame (e.g. mid-flyover), since the convergence point reads as a busy "starburst" up close.

These mirror gaps that exist in the Android renderer today, not something lost in the port — see the Android repo's README "Known gaps" section.

## License

MIT
