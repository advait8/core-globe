import { GlobeOptions, GlobeMarker, GlobeArc, GlobeConfig, Coordinates } from './types';
import { GlobeRenderer } from './GlobeRenderer';
import { GlobeAnimator } from './GlobeAnimator';

export class Globe {
  private renderer: GlobeRenderer;
  private animator: GlobeAnimator;
  private markers: Map<string, GlobeMarker> = new Map();
  private arcs: Map<string, GlobeArc> = new Map();

  constructor(options: GlobeOptions) {
    this.renderer = new GlobeRenderer(options.container, options.config ?? {}, options.dataBaseUrl ?? './data/', {
      onMarkerClick: options.onMarkerClick,
      onCountryClick: options.onCountryClick, // no-op today — see types.ts note
      onArcAnimationComplete: options.onArcAnimationComplete,
      onFlightComplete: options.onFlightComplete,
    });
    this.animator = new GlobeAnimator(this.renderer);
    this.renderer.start(() => options.onReady?.());
  }

  // ── Markers ──────────────────────────────────────────────────────────────

  addMarker(marker: GlobeMarker): this {
    this.markers.set(marker.id, marker);
    this.renderer.syncMarkers([...this.markers.values()]);
    return this;
  }

  removeMarker(id: string): this {
    this.markers.delete(id);
    this.renderer.syncMarkers([...this.markers.values()]);
    return this;
  }

  clearMarkers(): this {
    this.markers.clear();
    this.renderer.syncMarkers([]);
    return this;
  }

  // ── Arcs ─────────────────────────────────────────────────────────────────

  addArc(arc: GlobeArc): this {
    this.arcs.set(arc.id, { ...arc, progress: arc.progress ?? 1 });
    this.renderer.syncArcs([...this.arcs.values()]);
    return this;
  }

  animateArc(id: string): Promise<void> {
    return this.animator.animateArc(id);
  }

  removeArc(id: string): this {
    this.arcs.delete(id);
    this.renderer.syncArcs([...this.arcs.values()]);
    return this;
  }

  // ── Camera ───────────────────────────────────────────────────────────────

  flyTo(target: Coordinates): Promise<void> {
    return this.animator.flyTo(target);
  }

  // Cinematic flyover — the camera sweeps above the globe's surface along the
  // source→destination great-circle path and descends to a low hover over the
  // destination. `from` omitted starts from wherever the camera currently looks.
  animateFlight(target: Coordinates, from?: Coordinates): Promise<void> {
    return this.animator.animateFlight(target, from);
  }

  // ── Config ───────────────────────────────────────────────────────────────

  updateConfig(config: Partial<GlobeConfig>): this {
    this.renderer.updateConfig(config);
    return this;
  }

  setAutoRotate(enabled: boolean): this {
    this.renderer.updateConfig({ autoRotate: enabled });
    return this;
  }

  // ── Lifecycle ────────────────────────────────────────────────────────────

  resize(): this {
    this.renderer.resize();
    return this;
  }

  destroy(): void {
    this.renderer.destroy();
  }
}
