import * as THREE from 'three';
import { GlobeMarker, GlobeArc, GlobeConfig, MarkerStyle, ArcStyle, Coordinates } from './types';
import { slerpDirection, easeIn, easeInOut } from './GlobeMath';

// Colors/geometry constants — must match GlobeConfig.kt / GlobeRenderer.android.kt exactly.
const DEFAULTS = {
  backgroundColor: '#020B18',
  globeColor: '#0C1E3C',
  globeEmissive: '#010508',
  atmosphereColor: '#1A4088',
  gridColor: '#142D62',
  currentDotColor: '#4A9EFF',
  destinationDotColor: '#F5A623',
  arcColor: '#4A9EFF',
  landColor: '#C8B99A',
  borderColor: '#8B7355',
  cameraDistance: 5.0,
  tiltRadians: Math.PI / 4, // unused — see GlobeConfig.tiltRadians note in types.ts
} as const;

const ARC_TUBE_RADIUS = 0.005;
const ARC_ANIM_DURATION_MS = 1500;
const FLY_TO_DURATION_MS = 1500;
const FLY_TO_ZOOM_OUT = 1.4;
const CAMERA_FLIGHT_ALT_END = 1.12;
const CAMERA_FLIGHT_DURATION_MS = 6000;

const LAND_RADIUS = 1.0008;
const DOT_RADIUS_DEFAULT = 0.026;
const DOT_RADIUS_CURRENT = 0.034;
const CURRENT_RING_INNER_RADIUS = 0.062;
const CURRENT_RING_OUTER_RADIUS = 0.09;

const MIN_ZOOM = 2.5;
const MAX_ZOOM = 12.0;

type RequiredConfig = Required<GlobeConfig>;

interface MarkerObject {
  group: THREE.Group;
  dot: THREE.Mesh;
  labelSprite?: THREE.Sprite;
  markerType: 'default' | 'current' | 'destination';
  innerRing?: THREE.Mesh;
  outerRing?: THREE.Mesh;
}

interface ArcObject {
  object: THREE.Object3D;
  style: 'flight' | 'dashed' | 'trail' | 'custom';
  progress: number;
}

interface ArcAnimation {
  start: number;
  elapsed: number;
  onComplete?: () => void;
}

interface FlyToAnim {
  startY: number;
  targetY: number;
  startX: number;
  targetX: number;
  startZ: number;
  elapsed: number;
  onComplete?: () => void;
}

interface CameraFlightAnim {
  startDir: THREE.Vector3;
  startAlt: number;
  dstDir: THREE.Vector3;
  lat: number;
  lng: number;
  elapsed: number;
  onComplete?: () => void;
}

export interface GlobeRendererCallbacks {
  onMarkerClick?: (id: string) => void;
  // No equivalent exists in the source renderer today — see types.ts GlobeOptions note.
  onCountryClick?: (iso2: string) => void;
  onArcAnimationComplete?: (arcId: string) => void;
  onFlightComplete?: () => void;
  onDragEnd?: (rotationX: number, rotationY: number) => void;
}

export class GlobeRenderer {
  private scene: THREE.Scene;
  private camera: THREE.PerspectiveCamera;
  private renderer: THREE.WebGLRenderer;
  private world: THREE.Group;
  private globeMesh!: THREE.Mesh<THREE.SphereGeometry, THREE.MeshPhongMaterial>;
  private atmosphereMesh?: THREE.Mesh<THREE.SphereGeometry, THREE.MeshPhongMaterial>;
  private gridGroup?: THREE.Group;
  private starsPoints?: THREE.Points;
  private landMesh?: THREE.Mesh<THREE.SphereGeometry, THREE.MeshBasicMaterial>;
  private bordersGroup?: THREE.Group;

  private markers: Map<string, MarkerObject> = new Map();
  private arcs: Map<string, ArcObject> = new Map();
  private arcAnimations: Map<string, ArcAnimation> = new Map();
  private flyToAnim: FlyToAnim | null = null;
  private cameraFlightAnim: CameraFlightAnim | null = null;

  private animFrameId: number | null = null;
  private config: RequiredConfig;
  private isDragging = false;
  private previousPointer = { x: 0, y: 0 };
  private tapStart = { x: 0, y: 0 };
  private pinchStartDist: number | null = null;
  private pinchStartZ: number | null = null;
  private t = 0;
  private resizeObserver?: ResizeObserver;

  constructor(
    private container: HTMLElement,
    config: GlobeConfig,
    private dataBaseUrl: string,
    private callbacks: GlobeRendererCallbacks
  ) {
    this.config = this.mergeConfig(config);

    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(48, this.aspect, 0.1, 100);
    this.camera.position.z = this.config.cameraDistance;

    const canvas = document.createElement('canvas');
    container.appendChild(canvas);
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: false, preserveDrawingBuffer: true });
    this.renderer.setPixelRatio(1);
    this.renderer.setSize(container.clientWidth, container.clientHeight, false);
    this.renderer.setClearColor(hexToInt(this.config.backgroundColor), 1);

    this.world = new THREE.Group();
    this.world.rotation.x = Math.PI / 4; // tiltRadians has no effect — matches the source's hardcoded tilt
    this.scene.add(this.world);

    this.scene.add(new THREE.AmbientLight(0x6080b0, 1.6));
    const dirLight = new THREE.DirectionalLight(0xffffff, 1.1);
    dirLight.position.set(5, 3, 5);
    this.scene.add(dirLight);
    const fillLight = new THREE.DirectionalLight(0x223366, 0.5);
    fillLight.position.set(-5, -2, -3);
    this.scene.add(fillLight);

    this.buildGlobe();
    if (this.config.showAtmosphere) this.buildAtmosphere();
    if (this.config.showGrid) this.buildGrid();
    if (this.config.showStars) this.buildStars();
    if (this.config.showLand) this.loadLand();
    if (this.config.showBorders) this.loadBorders();

    this.setupInteraction();
    this.bindResizeObserver();
  }

  start(onReady?: () => void): void {
    this.animate();
    onReady?.();
  }

  // ── Markers ──────────────────────────────────────────────────────────────

  syncMarkers(markers: GlobeMarker[]): void {
    const incoming = new Map(markers.map((m) => [m.id, m]));
    for (const [id, obj] of this.markers) {
      if (!incoming.has(id)) {
        this.removeMarkerObject(id, obj);
      }
    }
    for (const marker of markers) {
      if (!this.markers.has(marker.id)) {
        this.markers.set(marker.id, this.buildMarker(marker));
      }
    }
  }

  // ── Arcs ─────────────────────────────────────────────────────────────────

  syncArcs(arcs: GlobeArc[]): void {
    const incoming = new Map(arcs.map((a) => [a.id, a]));
    for (const [id, obj] of this.arcs) {
      if (!incoming.has(id)) {
        this.world.remove(obj.object);
        this.arcs.delete(id);
        this.arcAnimations.delete(id);
      }
    }
    for (const arc of arcs) {
      const styleKey = arcStyleKey(arc.style);
      const existing = this.arcs.get(arc.id);
      if (!existing || existing.style !== styleKey) {
        if (existing) this.world.remove(existing.object);
        this.arcs.set(arc.id, this.buildArcObject(arc));
      }
    }
  }

  // Starts the setDrawRange animation for an already-added 'flight' arc. Mirrors
  // arcAnimations seeding in the source's addArc(); no-op for other styles (they always
  // render fully drawn).
  animateArc(id: string, onComplete?: () => void): void {
    const arc = this.arcs.get(id);
    if (!arc || arc.style !== 'flight') {
      onComplete?.();
      return;
    }
    this.arcAnimations.set(id, { start: arc.progress, elapsed: 0, onComplete });
  }

  // ── Camera ───────────────────────────────────────────────────────────────

  flyTo(target: Coordinates, onComplete?: () => void): void {
    const targetY = -degToRad(target.lng);
    const startY = this.world.rotation.y % (2 * Math.PI);
    let diff = targetY - startY;
    if (diff > Math.PI) diff -= 2 * Math.PI;
    if (diff < -Math.PI) diff += 2 * Math.PI;

    this.flyToAnim = {
      startY,
      targetY: startY + diff,
      startX: this.world.rotation.x,
      targetX: Math.PI / 4 - degToRad(target.lat) * 0.15,
      startZ: this.camera.position.z,
      elapsed: 0,
      onComplete,
    };
  }

  animateFlight(target: Coordinates, from: Coordinates | undefined, onComplete?: () => void): void {
    this.world.updateMatrixWorld(true);
    const startDir = from
      ? this.worldDirection(from.lat, from.lng)
      : this.camera.position.clone().normalize();

    this.cameraFlightAnim = {
      startDir,
      startAlt: this.camera.position.length(),
      dstDir: this.worldDirection(target.lat, target.lng),
      lat: target.lat,
      lng: target.lng,
      elapsed: 0,
      onComplete,
    };
  }

  private worldDirection(lat: number, lng: number): THREE.Vector3 {
    const p = latLngToThree(lat, lng, 1.0);
    return p.applyMatrix4(this.world.matrixWorld).normalize();
  }

  // ── Config ───────────────────────────────────────────────────────────────

  updateConfig(config: Partial<GlobeConfig>): void {
    const next = { ...this.config, ...stripUndefined(config) };
    this.config = next;

    // autoRotate/autoRotateSpeed are read live from this.config each frame in animate().
    this.renderer.setClearColor(hexToInt(next.backgroundColor), 1);
    this.globeMesh.material.color.set(hexToInt(next.globeColor));

    if (this.gridGroup) {
      this.gridGroup.visible = next.showGrid;
      this.gridGroup.children.forEach((line) => {
        ((line as THREE.Line).material as THREE.LineBasicMaterial).color.set(hexToInt(next.gridColor));
      });
    } else if (next.showGrid) {
      this.buildGrid();
    }

    if (this.atmosphereMesh) {
      this.atmosphereMesh.visible = next.showAtmosphere;
      this.atmosphereMesh.material.color.set(hexToInt(next.atmosphereColor));
    } else if (next.showAtmosphere) {
      this.buildAtmosphere();
    }

    if (this.starsPoints) {
      this.starsPoints.visible = next.showStars;
    } else if (next.showStars) {
      this.buildStars();
    }

    if (this.landMesh) {
      this.landMesh.visible = next.showLand;
    } else if (next.showLand) {
      this.loadLand();
    }

    if (this.bordersGroup) {
      this.bordersGroup.visible = next.showBorders;
    } else if (next.showBorders) {
      this.loadBorders();
    }

    // currentDotColor/destinationDotColor/arcColor/landColor/borderColor are baked into
    // each marker/arc/land/border object at creation time, matching the source (which has
    // no live-recolor path at all — every config field is baked into the initial HTML
    // string). Changing them here only affects objects added after the change.
  }

  resize(): void {
    this.camera.aspect = this.aspect;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(this.container.clientWidth, this.container.clientHeight, false);
  }

  destroy(): void {
    if (this.animFrameId !== null) cancelAnimationFrame(this.animFrameId);
    this.resizeObserver?.disconnect();
    this.renderer.dispose();
    this.renderer.domElement.remove();
  }

  private get aspect(): number {
    return this.container.clientWidth / this.container.clientHeight;
  }

  private mergeConfig(config: GlobeConfig): RequiredConfig {
    return {
      globeColor: config.globeColor ?? DEFAULTS.globeColor,
      gridColor: config.gridColor ?? DEFAULTS.gridColor,
      atmosphereColor: config.atmosphereColor ?? DEFAULTS.atmosphereColor,
      currentDotColor: config.currentDotColor ?? DEFAULTS.currentDotColor,
      destinationDotColor: config.destinationDotColor ?? DEFAULTS.destinationDotColor,
      arcColor: config.arcColor ?? DEFAULTS.arcColor,
      backgroundColor: config.backgroundColor ?? DEFAULTS.backgroundColor,
      showGrid: config.showGrid ?? true,
      showAtmosphere: config.showAtmosphere ?? true,
      showStars: config.showStars ?? true,
      showLand: config.showLand ?? true,
      landColor: config.landColor ?? DEFAULTS.landColor,
      showBorders: config.showBorders ?? true,
      borderColor: config.borderColor ?? DEFAULTS.borderColor,
      autoRotate: config.autoRotate ?? true,
      autoRotateSpeed: config.autoRotateSpeed ?? 0.0022,
      tiltRadians: config.tiltRadians ?? DEFAULTS.tiltRadians,
      cameraDistance: config.cameraDistance ?? DEFAULTS.cameraDistance,
    };
  }

  // ── Scene construction ──────────────────────────────────────────────────

  private buildGlobe(): void {
    const geo = new THREE.SphereGeometry(1, 64, 64);
    const mat = new THREE.MeshPhongMaterial({
      color: hexToInt(this.config.globeColor),
      emissive: hexToInt(DEFAULTS.globeEmissive),
      shininess: 18,
    });
    this.globeMesh = new THREE.Mesh(geo, mat);
    this.world.add(this.globeMesh);
  }

  private buildAtmosphere(): void {
    const geo = new THREE.SphereGeometry(1.022, 32, 32);
    const mat = new THREE.MeshPhongMaterial({
      color: hexToInt(this.config.atmosphereColor),
      transparent: true,
      opacity: 0.08,
      side: THREE.BackSide,
    });
    this.atmosphereMesh = new THREE.Mesh(geo, mat);
    this.world.add(this.atmosphereMesh);
  }

  private buildGrid(): void {
    const group = new THREE.Group();
    const mat = new THREE.LineBasicMaterial({
      color: hexToInt(this.config.gridColor),
      transparent: true,
      opacity: 0.5,
    });
    const r = 1.0015;

    for (const lat of [-60, -30, 0, 30, 60]) {
      const points: THREE.Vector3[] = [];
      for (let lng = 0; lng <= 360; lng += 2) points.push(latLngToThree(lat, lng - 180, r));
      group.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(points), mat));
    }
    for (let lng = -180; lng < 180; lng += 30) {
      const points: THREE.Vector3[] = [];
      for (let lat = -88; lat <= 88; lat += 2) points.push(latLngToThree(lat, lng, r));
      group.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(points), mat));
    }

    this.gridGroup = group;
    this.world.add(group);
  }

  private buildStars(): void {
    const verts: number[] = [];
    for (let i = 0; i < 1500; i++) {
      verts.push((Math.random() - 0.5) * 200, (Math.random() - 0.5) * 200, (Math.random() - 0.5) * 200);
    }
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(verts, 3));
    const mat = new THREE.PointsMaterial({ color: 0xffffff, size: 0.12, transparent: true, opacity: 0.75 });
    this.starsPoints = new THREE.Points(geo, mat);
    this.scene.add(this.starsPoints); // fixed to the scene, not the rotating world
  }

  private async loadLand(): Promise<void> {
    const res = await fetch(`${this.dataBaseUrl}land.geojson`);
    const geojson = await res.json();
    this.buildLand(geojson);
  }

  // Painted as a texture on a true sphere rather than a raised triangle mesh: flat
  // triangles spanning a whole continent chord well below the globe's curved surface and
  // z-fight with the ocean underneath. A texture has no geometry to sag, and evenodd fill
  // naturally cuts lake holes without touching the mesh. Ported from buildLand() exactly,
  // plus an antimeridian-crossing fix applied to both this and the Kotlin source (see
  // drawRingSplitAtAntimeridian below).
  private buildLand(geojson: GeoJsonFeatureCollection): void {
    const W = 2048;
    const H = 1024;
    const canvas = document.createElement('canvas');
    canvas.width = W;
    canvas.height = H;
    const ctx = canvas.getContext('2d')!;
    ctx.fillStyle = this.config.landColor;

    for (const feature of geojson.features) {
      const geom = feature.geometry;
      if (!geom) continue;
      const polygons = geom.type === 'Polygon' ? [geom.coordinates] : geom.coordinates;
      for (const polygon of polygons as number[][][][]) {
        ctx.beginPath();
        for (const ring of polygon) {
          drawRingSplitAtAntimeridian(ctx, ring, W, H);
        }
        ctx.fill('evenodd');
      }
    }

    const texture = new THREE.CanvasTexture(canvas);
    const geo = new THREE.SphereGeometry(LAND_RADIUS, 64, 64);
    const mat = new THREE.MeshBasicMaterial({ map: texture, transparent: true });
    this.landMesh = new THREE.Mesh(geo, mat);
    this.world.add(this.landMesh);
  }

  private async loadBorders(): Promise<void> {
    const res = await fetch(`${this.dataBaseUrl}countries.geojson`);
    const geojson = await res.json();
    this.buildBorders(geojson);
  }

  private buildBorders(geojson: GeoJsonFeatureCollection): void {
    const group = new THREE.Group();
    const mat = new THREE.LineBasicMaterial({
      color: hexToInt(this.config.borderColor),
      transparent: true,
      opacity: 0.5,
    });
    for (const feature of geojson.features) {
      const geom = feature.geometry;
      if (!geom) continue;
      const polygons = geom.type === 'Polygon' ? [geom.coordinates] : geom.coordinates;
      for (const polygon of polygons as number[][][][]) {
        for (const ring of polygon) {
          const points = ring.map((c) => latLngToThree(c[1], c[0], 1.002));
          group.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(points), mat));
        }
      }
    }
    this.bordersGroup = group;
    this.world.add(group);
  }

  private makeTextSprite(text: string, color: string): THREE.Sprite {
    const canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 80;
    const ctx = canvas.getContext('2d')!;
    ctx.font = 'bold 32px Arial, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    const metrics = ctx.measureText(text);
    const pw = metrics.width + 24;
    const ph = 44;
    const px = 256 - pw / 2;
    const py = 18;
    ctx.fillStyle = 'rgba(2,11,24,0.72)';
    ctx.beginPath();
    ctx.roundRect(px, py, pw, ph, 6);
    ctx.fill();
    ctx.fillStyle = color;
    ctx.fillText(text, 256, 40);
    const tex = new THREE.CanvasTexture(canvas);
    const mat = new THREE.SpriteMaterial({ map: tex, transparent: true, depthTest: false });
    const sprite = new THREE.Sprite(mat);
    sprite.scale.set(0.32, 0.05, 1.0);
    return sprite;
  }

  private buildMarker(m: GlobeMarker): MarkerObject {
    const group = new THREE.Group();
    const pos = latLngToThree(m.lat, m.lng, 1.012);
    group.position.copy(pos);
    group.lookAt(0, 0, 0);
    group.rotateX(Math.PI / 2);

    let dot: THREE.Mesh;
    let innerRing: THREE.Mesh | undefined;
    let outerRing: THREE.Mesh | undefined;
    let markerType: MarkerObject['markerType'] = 'default';

    if (m.style === 'current') {
      dot = new THREE.Mesh(
        new THREE.SphereGeometry(DOT_RADIUS_CURRENT, 16, 16),
        new THREE.MeshBasicMaterial({ color: hexToInt(this.config.currentDotColor) })
      );
      innerRing = new THREE.Mesh(
        new THREE.SphereGeometry(CURRENT_RING_INNER_RADIUS, 16, 16),
        new THREE.MeshBasicMaterial({ color: hexToInt(this.config.currentDotColor), transparent: true, opacity: 0.24 })
      );
      outerRing = new THREE.Mesh(
        new THREE.SphereGeometry(CURRENT_RING_OUTER_RADIUS, 16, 16),
        new THREE.MeshBasicMaterial({ color: hexToInt(this.config.currentDotColor), transparent: true, opacity: 0.1 })
      );
      group.add(dot, innerRing, outerRing);
      markerType = 'current';
    } else if (m.style === 'destination') {
      dot = new THREE.Mesh(
        new THREE.SphereGeometry(DOT_RADIUS_DEFAULT, 16, 16),
        new THREE.MeshBasicMaterial({ color: hexToInt(this.config.destinationDotColor) })
      );
      group.add(dot);
      markerType = 'destination';
    } else if (typeof m.style === 'object') {
      const size = (m.style.size ?? 1) * DOT_RADIUS_DEFAULT;
      dot = new THREE.Mesh(
        new THREE.SphereGeometry(size, 16, 16),
        new THREE.MeshBasicMaterial({ color: hexToInt(m.style.color || '#ffffff') })
      );
      group.add(dot);
      // pulse:true tags this as 'current'-typed for the animate() pulsar check below, but
      // (matching the source exactly) never gets the ring meshes 'current' markers get, so
      // nothing actually animates — see the MarkerStyle 'pulse' note in types.ts.
      markerType = m.style.pulse ? 'current' : 'default';
    } else {
      // 'default' and 'visited' both fall through to here — 'visited' has no distinct
      // look in the source renderer today, see types.ts note.
      dot = new THREE.Mesh(new THREE.SphereGeometry(DOT_RADIUS_DEFAULT, 16, 16), new THREE.MeshBasicMaterial({ color: 0xffffff }));
      group.add(dot);
      markerType = 'default';
    }

    dot.userData.markerId = m.id;

    let labelSprite: THREE.Sprite | undefined;
    if (m.label) {
      const labelColor = m.style === 'current' ? this.config.currentDotColor : this.config.destinationDotColor;
      labelSprite = this.makeTextSprite(m.label, labelColor);
      labelSprite.position.copy(latLngToThree(m.lat, m.lng, 1.09));
      this.world.add(labelSprite);
    }

    this.world.add(group);
    return { group, dot, labelSprite, markerType, innerRing, outerRing };
  }

  private removeMarkerObject(id: string, obj: MarkerObject): void {
    if (obj.labelSprite) this.world.remove(obj.labelSprite);
    this.world.remove(obj.group);
    this.markers.delete(id);
  }

  private arcCurvePoints(a: GlobeArc): THREE.Vector3[] {
    const A = latLngToThree(a.from.lat, a.from.lng, 1.0);
    const B = latLngToThree(a.to.lat, a.to.lng, 1.0);
    const ctrl = A.clone().add(B).normalize().multiplyScalar(1.48);
    return new THREE.QuadraticBezierCurve3(A, ctrl, B).getPoints(100);
  }

  private buildDashedArc(a: GlobeArc): THREE.Line {
    const points = this.arcCurvePoints(a);
    const geo = new THREE.BufferGeometry().setFromPoints(points);
    const mat = new THREE.LineDashedMaterial({
      color: hexToInt(this.config.arcColor),
      dashSize: 0.06,
      gapSize: 0.04,
      transparent: true,
      opacity: 0.45,
    });
    const line = new THREE.Line(geo, mat);
    line.computeLineDistances();
    return line;
  }

  private buildTubeArc(a: GlobeArc, style: 'flight' | 'trail' | 'custom'): THREE.Mesh {
    const points = this.arcCurvePoints(a);
    const catmull = new THREE.CatmullRomCurve3(points);

    let colorHex = this.config.arcColor;
    let widthScale = 1.0;
    let opacity = 0.75;
    if (style === 'trail') {
      opacity = 0.28;
      widthScale = 0.8;
    } else if (style === 'custom' && typeof a.style === 'object') {
      colorHex = a.style.color || colorHex;
      widthScale = a.style.width ?? 1.0;
    }

    const geo = new THREE.TubeGeometry(catmull, 100, ARC_TUBE_RADIUS * widthScale, 6, false);
    const mat = new THREE.MeshBasicMaterial({ color: hexToInt(colorHex), transparent: true, opacity });
    const tube = new THREE.Mesh(geo, mat);

    const progress = a.progress ?? 1.0;
    if (progress < 1.0 && geo.index) {
      geo.setDrawRange(0, Math.floor(geo.index.count * progress));
    }
    return tube;
  }

  private buildArcObject(a: GlobeArc): ArcObject {
    const styleKey = arcStyleKey(a.style);
    const object = styleKey === 'dashed' ? this.buildDashedArc(a) : this.buildTubeArc(a, styleKey as 'flight' | 'trail' | 'custom');
    object.userData.arcId = a.id;
    object.userData.style = styleKey;
    this.world.add(object);

    const progress = a.progress ?? 1.0;
    if (styleKey === 'flight' && progress < 1.0) {
      this.arcAnimations.set(a.id, { start: progress, elapsed: 0 });
    }
    return { object, style: styleKey, progress };
  }

  // ── Interaction ──────────────────────────────────────────────────────────

  private setupInteraction(): void {
    const canvas = this.renderer.domElement;

    const onPointerDown = (x: number, y: number) => {
      this.isDragging = true;
      this.previousPointer = { x, y };
    };
    const onPointerMove = (x: number, y: number) => {
      if (!this.isDragging) return;
      const dx = x - this.previousPointer.x;
      const dy = y - this.previousPointer.y;
      this.world.rotation.y += dx * 0.005;
      this.world.rotation.x = clamp(this.world.rotation.x + dy * 0.005, -0.7, 0.7);
      this.previousPointer = { x, y };
    };
    const onPointerUp = (x: number, y: number, isTap: boolean) => {
      if (isTap && Math.abs(x - this.tapStart.x) < 5 && Math.abs(y - this.tapStart.y) < 5) {
        this.handleTap(x, y);
      }
      this.isDragging = false;
      this.callbacks.onDragEnd?.(this.world.rotation.x, this.world.rotation.y);
    };

    canvas.addEventListener('mousedown', (e) => {
      this.tapStart = { x: e.clientX, y: e.clientY };
      onPointerDown(e.clientX, e.clientY);
    });
    canvas.addEventListener('mousemove', (e) => onPointerMove(e.clientX, e.clientY));
    canvas.addEventListener('mouseup', (e) => onPointerUp(e.clientX, e.clientY, true));
    canvas.addEventListener(
      'wheel',
      (e) => {
        e.preventDefault();
        this.camera.position.z = clamp(this.camera.position.z + e.deltaY * 0.01, MIN_ZOOM, MAX_ZOOM);
      },
      { passive: false }
    );

    canvas.addEventListener(
      'touchstart',
      (e) => {
        e.preventDefault();
        if (e.touches.length === 2) {
          this.pinchStartDist = pinchDist(e.touches);
          this.pinchStartZ = this.camera.position.z;
          this.isDragging = false;
          return;
        }
        const t0 = e.touches[0];
        this.tapStart = { x: t0.clientX, y: t0.clientY };
        onPointerDown(t0.clientX, t0.clientY);
      },
      { passive: false }
    );

    canvas.addEventListener(
      'touchmove',
      (e) => {
        e.preventDefault();
        if (e.touches.length === 2 && this.pinchStartDist !== null && this.pinchStartZ !== null) {
          const scale = this.pinchStartDist / pinchDist(e.touches);
          this.camera.position.z = clamp(this.pinchStartZ * scale, MIN_ZOOM, MAX_ZOOM);
          return;
        }
        if (e.touches.length === 1) onPointerMove(e.touches[0].clientX, e.touches[0].clientY);
      },
      { passive: false }
    );

    canvas.addEventListener(
      'touchend',
      (e) => {
        e.preventDefault();
        this.pinchStartDist = null;
        if (e.touches.length === 0) {
          const t0 = e.changedTouches[0];
          onPointerUp(t0.clientX, t0.clientY, true);
        }
      },
      { passive: false }
    );
  }

  private bindResizeObserver(): void {
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(this.container);
  }

  private handleTap(clientX: number, clientY: number): void {
    const rect = this.renderer.domElement.getBoundingClientRect();
    const mouse = new THREE.Vector2(
      ((clientX - rect.left) / rect.width) * 2 - 1,
      -((clientY - rect.top) / rect.height) * 2 + 1
    );
    const raycaster = new THREE.Raycaster();
    raycaster.setFromCamera(mouse, this.camera);

    const dots = Array.from(this.markers.values()).map((m) => m.dot);
    const hits = raycaster.intersectObjects(dots);
    if (hits.length > 0) {
      const markerId = hits[0].object.userData.markerId as string;
      this.callbacks.onMarkerClick?.(markerId);
    }
    // No border/country hit-testing today — see onCountryClick note in types.ts.
  }

  // ── Render loop ──────────────────────────────────────────────────────────

  private animate = (): void => {
    this.animFrameId = requestAnimationFrame(this.animate);
    this.t += 0.016;
    const FRAME_DT = 0.016;

    if (this.cameraFlightAnim) {
      const anim = this.cameraFlightAnim;
      anim.elapsed += FRAME_DT * 1000;
      const flightT = Math.min(1, anim.elapsed / CAMERA_FLIGHT_DURATION_MS);

      const dir = slerpDirection(anim.startDir, anim.dstDir, easeInOut(flightT));
      const alt = lerp(anim.startAlt, CAMERA_FLIGHT_ALT_END, easeIn(flightT));
      this.camera.position.set(dir.x * alt, dir.y * alt, dir.z * alt);
      this.camera.lookAt(dir.x, dir.y, dir.z);

      if (anim.elapsed >= CAMERA_FLIGHT_DURATION_MS) {
        this.world.rotation.y = -degToRad(anim.lng);
        this.world.rotation.x = Math.PI / 4 - degToRad(anim.lat) * 0.15;
        this.camera.position.set(0, 0, this.config.cameraDistance);
        this.camera.rotation.set(0, 0, 0);
        this.cameraFlightAnim = null;
        anim.onComplete?.();
        this.callbacks.onFlightComplete?.();
      }
    } else if (this.flyToAnim) {
      const anim = this.flyToAnim;
      anim.elapsed += FRAME_DT * 1000;
      const flyT = easeInOut(Math.min(1, anim.elapsed / FLY_TO_DURATION_MS));
      this.world.rotation.y = lerp(anim.startY, anim.targetY, flyT);
      this.world.rotation.x = lerp(anim.startX, anim.targetX, flyT);

      const pullBack = Math.sin(Math.PI * flyT) * FLY_TO_ZOOM_OUT;
      this.camera.position.z = Math.min(MAX_ZOOM, anim.startZ + pullBack);

      if (anim.elapsed >= FLY_TO_DURATION_MS) {
        this.camera.position.z = anim.startZ;
        this.flyToAnim = null;
        anim.onComplete?.();
        this.callbacks.onDragEnd?.(this.world.rotation.x, this.world.rotation.y);
      }
    } else if (this.config.autoRotate && !this.isDragging) {
      this.world.rotation.y += this.config.autoRotateSpeed;
    }

    for (const [id, anim] of this.arcAnimations) {
      const arc = this.arcs.get(id);
      const mesh = arc?.object as THREE.Mesh | undefined;
      if (!mesh || !(mesh.geometry as THREE.BufferGeometry).index) {
        this.arcAnimations.delete(id);
        continue;
      }
      anim.elapsed += FRAME_DT * 1000;
      const arcT = easeInOut(Math.min(1, anim.elapsed / ARC_ANIM_DURATION_MS));
      const progress = anim.start + (1.0 - anim.start) * arcT;
      const total = (mesh.geometry as THREE.BufferGeometry).index!.count;
      (mesh.geometry as THREE.BufferGeometry).setDrawRange(0, Math.floor(total * progress));
      if (anim.elapsed >= ARC_ANIM_DURATION_MS) {
        this.arcAnimations.delete(id);
        anim.onComplete?.();
        this.callbacks.onArcAnimationComplete?.(id);
      }
    }

    this.world.updateMatrixWorld(true);
    const camDir = this.camera.position.clone().normalize();

    for (const marker of this.markers.values()) {
      const worldNormal = marker.group.position.clone().applyMatrix4(this.world.matrixWorld).normalize();
      const facingCamera = worldNormal.dot(camDir) > 0;
      marker.group.visible = facingCamera;
      if (marker.labelSprite) marker.labelSprite.visible = facingCamera;

      if (marker.markerType === 'current' && marker.innerRing && marker.outerRing) {
        const s1 = 1 + 0.55 * Math.abs(Math.sin(this.t * 1.6));
        marker.innerRing.scale.setScalar(s1);
        (marker.innerRing.material as THREE.MeshBasicMaterial).opacity = 0.24 - 0.15 * Math.abs(Math.sin(this.t * 1.6));
        const s2 = 1 + 0.55 * Math.abs(Math.sin(this.t * 1.6 + 0.8));
        marker.outerRing.scale.setScalar(s2);
        (marker.outerRing.material as THREE.MeshBasicMaterial).opacity = 0.1 - 0.07 * Math.abs(Math.sin(this.t * 1.6 + 0.8));
      }
    }

    this.renderer.render(this.scene, this.camera);
  };
}

interface GeoJsonFeatureCollection {
  features: Array<{ geometry: { type: string; coordinates: unknown } | null }>;
}

function arcStyleKey(style: ArcStyle | undefined): 'flight' | 'dashed' | 'trail' | 'custom' {
  if (!style || style === 'flight') return 'flight';
  if (style === 'dashed') return 'dashed';
  if (style === 'trail') return 'trail';
  return 'custom';
}

function latLngToThree(lat: number, lng: number, radius = 1): THREE.Vector3 {
  const phi = ((90 - lat) * Math.PI) / 180;
  const theta = ((lng + 180) * Math.PI) / 180;
  return new THREE.Vector3(-radius * Math.sin(phi) * Math.cos(theta), radius * Math.cos(phi), radius * Math.sin(phi) * Math.sin(theta));
}

function hexToInt(hex: string): number {
  return parseInt(hex.replace('#', ''), 16);
}

function degToRad(deg: number): number {
  return (deg * Math.PI) / 180;
}

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}

function clamp(x: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, x));
}

function pinchDist(touches: TouchList): number {
  const dx = touches[0].clientX - touches[1].clientX;
  const dy = touches[0].clientY - touches[1].clientY;
  return Math.sqrt(dx * dx + dy * dy);
}

function stripUndefined<T extends object>(obj: T): Partial<T> {
  return Object.fromEntries(Object.entries(obj).filter(([, v]) => v !== undefined)) as Partial<T>;
}

// A GeoJSON ring can cross the antimeridian (e.g. Antarctica's closing edge runs
// lng 180 -> -180 along lat -90 in land.geojson). Connecting those two points directly
// draws a spurious straight line across nearly the whole canvas width. Starting a new
// subpath at the jump instead of connecting across it avoids drawing that line — canvas
// fill() auto-closes each open subpath, so no explicit closePath() is needed. This isn't
// full antimeridian polygon clipping (the resulting fragment's auto-close is a straight
// chord, not a proper clip to +-180), but for a decorative texture fill it's sufficient
// and matches the one real crossing in the data. Note: confirmed this fixes a real stray
// line but is NOT the cause of any pole-convergence visual — that's buildGrid()'s
// meridians meeting at the pole, which is correct/expected and unrelated to this fix.
function drawRingSplitAtAntimeridian(ctx: CanvasRenderingContext2D, ring: number[][], W: number, H: number): void {
  let prevLng: number | null = null;
  for (const c of ring) {
    const x = ((c[0] + 180) / 360) * W;
    const y = ((90 - c[1]) / 180) * H;
    if (prevLng === null || Math.abs(c[0] - prevLng) > 180) {
      ctx.moveTo(x, y);
    } else {
      ctx.lineTo(x, y);
    }
    prevLng = c[0];
  }
}
