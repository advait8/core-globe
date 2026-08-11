export interface Vector3 {
  x: number;
  y: number;
  z: number;
}

export function latLngTo3D(lat: number, lng: number, radius = 1): Vector3 {
  const phi = ((90 - lat) * Math.PI) / 180;
  const theta = ((lng + 180) * Math.PI) / 180;
  return {
    x: -radius * Math.sin(phi) * Math.cos(theta),
    y: radius * Math.cos(phi),
    z: radius * Math.sin(phi) * Math.sin(theta),
  };
}

export function arcPoint(
  from: { lat: number; lng: number },
  to: { lat: number; lng: number },
  t: number,
  lift = 1.48 // matches arcCurvePoints()'s control-point multiplier in the source exactly
): Vector3 {
  const A = latLngTo3D(from.lat, from.lng);
  const B = latLngTo3D(to.lat, to.lng);
  const ctrl = normalize(add(A, B));
  return quadraticBezier(A, scale(ctrl, lift), B, t);
}

export function latLngToRotation(lat: number, lng: number) {
  return {
    rx: (-lat * Math.PI) / 180,
    ry: (lng * Math.PI) / 180,
  };
}

// Spherical-linear interpolation between two unit vectors — the camera-flyover sweep
// direction. Ports slerpDirection() from the source renderer exactly.
export function slerpDirection(a: Vector3, b: Vector3, t: number): Vector3 {
  const dot = clamp(dotProduct(a, b), -1, 1);
  const theta = Math.acos(dot) * t;
  const relative = normalizeOrZero(addScaled(b, a, -dot));
  return addScaled(scale(a, Math.cos(theta)), relative, Math.sin(theta));
}

export function easeInOut(x: number): number {
  return x < 0.5 ? 2 * x * x : -1 + (4 - 2 * x) * x;
}

export function easeIn(x: number): number {
  return x * x;
}

function add(a: Vector3, b: Vector3): Vector3 {
  return { x: a.x + b.x, y: a.y + b.y, z: a.z + b.z };
}
function addScaled(a: Vector3, b: Vector3, s: number): Vector3 {
  return { x: a.x + b.x * s, y: a.y + b.y * s, z: a.z + b.z * s };
}
function scale(v: Vector3, s: number): Vector3 {
  return { x: v.x * s, y: v.y * s, z: v.z * s };
}
function dotProduct(a: Vector3, b: Vector3): number {
  return a.x * b.x + a.y * b.y + a.z * b.z;
}
function length(v: Vector3): number {
  return Math.sqrt(dotProduct(v, v));
}
function normalize(v: Vector3): Vector3 {
  return scale(v, 1 / length(v));
}
function normalizeOrZero(v: Vector3): Vector3 {
  const lenSq = dotProduct(v, v);
  return lenSq < 1e-10 ? { x: 0, y: 0, z: 0 } : scale(v, 1 / Math.sqrt(lenSq));
}
function clamp(x: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, x));
}
function quadraticBezier(a: Vector3, ctrl: Vector3, b: Vector3, t: number): Vector3 {
  const u = 1 - t;
  return add(add(scale(a, u * u), scale(ctrl, 2 * u * t)), scale(b, t * t));
}
