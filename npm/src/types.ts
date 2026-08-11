export interface Coordinates {
  lat: number;
  lng: number;
}

// 'visited' is accepted for API parity with the Kotlin sealed class but currently renders
// identically to 'default' — the source renderer's addMarker() has no 'visited' branch.
export type MarkerStyle =
  | 'default'
  | 'current'
  | 'destination'
  | 'visited'
  | {
      color: string; // hex, e.g. "#FF6B6B" — maps to MarkerStyle.Custom.colorHex
      size?: number; // multiplier, default 1.0 — MarkerStyle.Custom.size
      pulse?: boolean; // MarkerStyle.Custom.pulse — see GlobeRenderer.ts addMarker() note:
      // this flag is a no-op today in the ported source too (it tags the marker as
      // 'current'-typed for the animate() pulsar check, but never attaches the pulsing
      // ring meshes 'current' markers get, so nothing actually animates).
    };

// 'flight' draws in over ~1.5s when progress < 1. 'dashed' and 'trail' always render fully
// drawn ('dashed' = potential destination, low opacity; 'trail' = past leg, dimmer).
export type ArcStyle =
  | 'flight'
  | 'dashed'
  | 'trail'
  | {
      color?: string; // ArcStyle.Custom.colorHex
      width?: number; // ArcStyle.Custom.width, multiplier default 1.0
    };

export interface GlobeMarker {
  id: string;
  lat: number;
  lng: number;
  style?: MarkerStyle;
  label?: string;
}

export interface GlobeArc {
  id: string;
  from: Coordinates;
  to: Coordinates;
  style?: ArcStyle;
  progress?: number; // 0-1, default 1 (fully drawn)
}

export interface GlobeConfig {
  globeColor?: string; // default: '#0C1E3C'
  gridColor?: string; // default: '#142D62'
  atmosphereColor?: string; // default: '#1A4088'
  currentDotColor?: string; // default: '#4A9EFF'
  destinationDotColor?: string; // default: '#F5A623'
  arcColor?: string; // default: '#4A9EFF'
  backgroundColor?: string; // default: '#020B18'
  showGrid?: boolean; // default: true
  showAtmosphere?: boolean; // default: true
  showStars?: boolean; // default: true
  showLand?: boolean; // default: true
  landColor?: string; // default: '#C8B99A'
  showBorders?: boolean; // default: true
  borderColor?: string; // default: '#8B7355'
  autoRotate?: boolean; // default: true
  autoRotateSpeed?: number; // default: 0.0022 rad/frame
  tiltRadians?: number; // default: Math.PI / 4 — currently a no-op, mirrors the Kotlin gap
  cameraDistance?: number; // default: 5.0
}

export interface GlobeOptions {
  container: HTMLElement;
  config?: GlobeConfig;
  // Base URL the renderer fetches land.geojson/countries.geojson relative to (they're too
  // large to bundle into the core module — see vite.config.ts). Defaults to './data/', which
  // matches serving the whole npm package's dist/ folder together (e.g. via jsdelivr, or
  // `npx serve npm/` for local testing). Point this at wherever you host dist/data/ if you
  // only ship the JS file itself.
  dataBaseUrl?: string;
  onMarkerClick?: (markerId: string) => void;
  // NOTE: no equivalent exists in the source renderer today — border lines aren't tagged
  // with ISO2 codes and aren't included in the tap raycast at all. This is new
  // functionality, not a port: real per-country hit-testing (tagging each polygon's line
  // objects with the feature's ISO2 property and adding them to the raycast targets) has
  // not been implemented. This callback is typed for the planned API but never fires.
  onCountryClick?: (iso2: string) => void;
  onArcAnimationComplete?: (arcId: string) => void;
  onFlightComplete?: () => void;
  onReady?: () => void;
}
