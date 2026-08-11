import { Coordinates } from './types';
import { GlobeRenderer } from './GlobeRenderer';

// Thin Promise wrapper around GlobeRenderer's own timelines. The renderer owns all the
// per-frame math (easing, camera pull-back, great-circle slerp) in its single animate()
// loop, mirroring the source's animate() — this class only turns "call me back when done"
// into a Promise for the public Globe API.
export class GlobeAnimator {
  constructor(private renderer: GlobeRenderer) {}

  animateArc(id: string): Promise<void> {
    return new Promise((resolve) => this.renderer.animateArc(id, resolve));
  }

  flyTo(target: Coordinates): Promise<void> {
    return new Promise((resolve) => this.renderer.flyTo(target, resolve));
  }

  animateFlight(target: Coordinates, from?: Coordinates): Promise<void> {
    return new Promise((resolve) => this.renderer.animateFlight(target, from, resolve));
  }
}
