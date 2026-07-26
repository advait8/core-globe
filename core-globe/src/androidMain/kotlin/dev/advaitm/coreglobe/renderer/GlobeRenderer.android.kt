package dev.advaitm.coreglobe.renderer

import android.content.Context
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.advaitm.coreglobe.api.*
import dev.advaitm.coreglobe.bridge.GlobeBridge
import org.json.JSONArray
import org.json.JSONObject

actual class GlobeRenderer(private val context: Context) {

    private lateinit var webView: WebView
    var onBridgeEvent: ((String) -> Unit)? = null
    private var isPageReady = false
    private var pendingState: GlobeState? = null

    actual fun initialize(state: GlobeState): Any {
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            addJavascriptInterface(GlobeBridge { json -> onBridgeEvent?.invoke(json) }, "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    isPageReady = true
                    pendingState?.let { s ->
                        pendingState = null
                        evaluateJs(stateToJson(s))
                    }
                }
                override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
                    return when (url) {
                        "https://localhost/three.min.js" -> WebResourceResponse(
                            "application/javascript", "UTF-8",
                            context.assets.open("three.min.js")
                        )
                        "https://localhost/countries.geojson" -> WebResourceResponse(
                            "application/json", "UTF-8",
                            context.assets.open("countries.geojson")
                        )
                        "https://localhost/land.geojson" -> WebResourceResponse(
                            "application/json", "UTF-8",
                            context.assets.open("land.geojson")
                        )
                        else -> super.shouldInterceptRequest(view, url)
                    }
                }
            }
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            loadDataWithBaseURL(
                "https://localhost/",
                buildGlobeHtml(state.config),
                "text/html",
                "UTF-8",
                null
            )
        }
        return webView
    }

    actual fun updateState(state: GlobeState) {
        if (!isPageReady) {
            pendingState = state
            return
        }
        evaluateJs(stateToJson(state))
    }

    fun flyTo(target: Coordinates) {
        evaluateScript("if(typeof flyTo==='function')flyTo(${target.lat}, ${target.lng})")
    }

    fun animateFlight(target: Coordinates, from: Coordinates? = null) {
        val srcArgs = if (from != null) "${from.lat}, ${from.lng}" else "null, null"
        evaluateScript("if(typeof animateFlight==='function')animateFlight($srcArgs, ${target.lat}, ${target.lng})")
    }

    private fun evaluateJs(json: String) {
        evaluateScript("if(typeof updateGlobe==='function')updateGlobe($json)")
    }

    private fun evaluateScript(script: String) {
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    actual fun destroy() {
        webView.destroy()
    }

    private fun stateToJson(state: GlobeState): String {
        val obj = JSONObject()

        if (!state.isDragging) {
            obj.put("rotation", JSONObject().apply {
                put("x", state.rotationX)
                put("y", state.rotationY)
            })
        }

        val markersArr = JSONArray()
        state.markers.forEach { marker ->
            markersArr.put(JSONObject().apply {
                put("id", marker.id)
                put("lat", marker.lat)
                put("lng", marker.lng)
                put("style", when (marker.style) {
                    is MarkerStyle.Current     -> "current"
                    is MarkerStyle.Destination -> "destination"
                    is MarkerStyle.Visited     -> "visited"
                    is MarkerStyle.Custom      -> "custom"
                    else                       -> "default"
                })
                if (marker.style is MarkerStyle.Custom) {
                    put("colorHex", marker.style.colorHex)
                    put("size", marker.style.size)
                    put("pulse", marker.style.pulse)
                }
                marker.label?.let { put("label", it) }
            })
        }
        obj.put("markers", markersArr)

        val arcsArr = JSONArray()
        state.arcs.forEach { arc ->
            arcsArr.put(JSONObject().apply {
                put("id", arc.id)
                put("fromLat", arc.from.lat)
                put("fromLng", arc.from.lng)
                put("toLat", arc.to.lat)
                put("toLng", arc.to.lng)
                put("progress", arc.animationProgress)
                put("style", when (arc.style) {
                    is ArcStyle.Flight -> "flight"
                    is ArcStyle.Dashed -> "dashed"
                    is ArcStyle.Trail  -> "trail"
                    is ArcStyle.Custom -> "custom"
                })
                if (arc.style is ArcStyle.Custom) {
                    put("colorHex", arc.style.colorHex)
                    put("width", arc.style.width)
                }
            })
        }
        obj.put("arcs", arcsArr)

        obj.put("config", JSONObject().apply {
            put("autoRotate", state.config.autoRotate)
            put("autoRotateSpeed", state.config.autoRotateSpeed)
            put("showGrid", state.config.showGrid)
        })

        return obj.toString()
    }
}

private fun buildGlobeHtml(config: GlobeConfig): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no"/>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body { width: 100%; height: 100%; overflow: hidden; background: ${config.backgroundColor}; }
  #c {
    position: fixed; top: 0; left: 0;
    width: 100%; height: 100%;
    display: block;
    -webkit-transform: translateZ(0);
    transform: translateZ(0);
  }
</style>
</head>
<body>
<canvas id="c"></canvas>
<script src="three.min.js"></script>
<script>
var scene, camera, renderer, world;
var markers = {};
var arcs = {};
var autoRotate = ${config.autoRotate};
var autoRotateSpeed = ${config.autoRotateSpeed};
var showGrid = ${config.showGrid};
var isDragging = false;
var previousMouseX = 0, previousMouseY = 0;
var t = 0;

var ARC_TUBE_RADIUS = 0.005;
var ARC_ANIM_DURATION = 1.5;
var FLY_TO_DURATION = 1.5;
var FLY_TO_ZOOM_OUT = 1.4;
var CAMERA_FLIGHT_ALT_END  = 1.12; // low hover altitude arriving above the destination
var CAMERA_FLIGHT_DURATION = 6.0;
var FRAME_DT = 0.016;
var arcAnimations = {};
var flyToAnim = null;
var cameraFlightAnim = null;

var LAND_RADIUS = 1.0008;

var DOT_RADIUS_DEFAULT = 0.026;
var DOT_RADIUS_CURRENT = 0.034;
var CURRENT_RING_INNER_RADIUS = 0.062;
var CURRENT_RING_OUTER_RADIUS = 0.09;

function easeInOut(x) {
    return x < 0.5 ? 2 * x * x : -1 + (4 - 2 * x) * x;
}

function easeIn(x) {
    return x * x;
}

// Spherical-linear interpolation between two unit vectors, i.e. a point `t` of the way
// along the great-circle arc from `a` to `b`. Used to sweep the flyover camera's view
// direction across the globe's surface independently of its altitude, so the camera
// visibly travels the whole path rather than just shrinking straight down onto one spot.
function slerpDirection(a, b, t) {
    var dot = THREE.MathUtils.clamp(a.dot(b), -1, 1);
    var theta = Math.acos(dot) * t;
    var relative = b.clone().addScaledVector(a, -dot);
    if (relative.lengthSq() < 1e-10) return a.clone();
    relative.normalize();
    return a.clone().multiplyScalar(Math.cos(theta)).addScaledVector(relative, Math.sin(theta));
}

function latLngTo3D(lat, lng, r) {
    r = r || 1;
    var phi   = (90 - lat) * Math.PI / 180;
    var theta = (lng + 180) * Math.PI / 180;
    return new THREE.Vector3(
        -r * Math.sin(phi) * Math.cos(theta),
         r * Math.cos(phi),
         r * Math.sin(phi) * Math.sin(theta)
    );
}

function hexToInt(hex) {
    return parseInt(hex.replace('#', ''), 16);
}

function init() {
    scene = new THREE.Scene();

    var W = window.innerWidth, H = window.innerHeight;
    camera = new THREE.PerspectiveCamera(48, W / H, 0.1, 100);
    camera.position.z = ${config.cameraDistance};

    var canvas = document.getElementById('c');
    renderer = new THREE.WebGLRenderer({ canvas: canvas, antialias: false, preserveDrawingBuffer: true });
    renderer.setPixelRatio(1);
    renderer.setSize(W, H, false);
    renderer.setClearColor(hexToInt('${config.backgroundColor}'), 1);

    world = new THREE.Group();
    world.rotation.x = Math.PI / 4;
    scene.add(world);

    // Ambient + directional light
    scene.add(new THREE.AmbientLight(0x6080B0, 1.6));
    var dirLight = new THREE.DirectionalLight(0xffffff, 1.1);
    dirLight.position.set(5, 3, 5);
    scene.add(dirLight);
    var fillLight = new THREE.DirectionalLight(0x223366, 0.5);
    fillLight.position.set(-5, -2, -3);
    scene.add(fillLight);

    // Globe sphere
    var globeGeo = new THREE.SphereGeometry(1, 64, 64);
    var globeMat = new THREE.MeshPhongMaterial({
        color: hexToInt('${config.globeColor}'),
        emissive: 0x010508,
        shininess: 18
    });
    world.add(new THREE.Mesh(globeGeo, globeMat));

    // Atmosphere
    if (${config.showAtmosphere}) {
        var atmGeo = new THREE.SphereGeometry(1.022, 32, 32);
        var atmMat = new THREE.MeshPhongMaterial({
            color: hexToInt('${config.atmosphereColor}'),
            transparent: true,
            opacity: 0.08,
            side: THREE.BackSide
        });
        world.add(new THREE.Mesh(atmGeo, atmMat));
    }

    // Grid
    if (${config.showGrid}) {
        buildGrid();
    }

    // Stars
    if (${config.showStars}) {
        buildStars();
    }

    // Land fill: continuous landmass shapes, no lake holes or country subdivisions.
    if (${config.showLand}) {
        fetch('land.geojson')
            .then(function(r) { return r.json(); })
            .then(function(data) { buildLand(data); });
    }

    // Country borders: separate admin-0 dataset, only fetched when shown.
    if (${config.showBorders}) {
        fetch('countries.geojson')
            .then(function(r) { return r.json(); })
            .then(function(data) { buildBorders(data); });
    }

    setupInteraction();
    animate();
}

function buildGrid() {
    var gridMat = new THREE.LineBasicMaterial({
        color: hexToInt('${config.gridColor}'),
        transparent: true,
        opacity: 0.5
    });
    var r = 1.0015;

    // Latitude lines
    [-60, -30, 0, 30, 60].forEach(function(lat) {
        var points = [];
        for (var lng = 0; lng <= 360; lng += 2) {
            points.push(latLngTo3D(lat, lng - 180, r));
        }
        var geo = new THREE.BufferGeometry().setFromPoints(points);
        world.add(new THREE.Line(geo, gridMat));
    });

    // Longitude lines
    for (var lng = -180; lng < 180; lng += 30) {
        var points = [];
        for (var lat = -88; lat <= 88; lat += 2) {
            points.push(latLngTo3D(lat, lng, r));
        }
        var geo = new THREE.BufferGeometry().setFromPoints(points);
        world.add(new THREE.Line(geo, gridMat));
    }
}

function buildStars() {
    var starVerts = [];
    for (var i = 0; i < 1500; i++) {
        starVerts.push(
            (Math.random() - 0.5) * 200,
            (Math.random() - 0.5) * 200,
            (Math.random() - 0.5) * 200
        );
    }
    var starGeo = new THREE.BufferGeometry();
    starGeo.setAttribute('position', new THREE.Float32BufferAttribute(starVerts, 3));
    var starMat = new THREE.PointsMaterial({
        color: 0xffffff,
        size: 0.12,
        transparent: true,
        opacity: 0.75
    });
    scene.add(new THREE.Points(starGeo, starMat));
}

function buildLand(geojson) {
    // Painted as a texture on a true sphere rather than a raised triangle mesh:
    // flat triangles spanning a whole continent (e.g. Sahara) chord well below
    // the globe's curved surface and z-fight with the ocean underneath. A
    // texture has no geometry to sag, so coastlines stay clean at any size,
    // and evenodd fill naturally cuts lake holes without touching the mesh.
    var W = 2048, H = 1024;
    var canvas = document.createElement('canvas');
    canvas.width = W;
    canvas.height = H;
    var ctx = canvas.getContext('2d');
    ctx.fillStyle = '${config.landColor}';

    geojson.features.forEach(function(feature) {
        var geom = feature.geometry;
        if (!geom) return;
        var polygons = geom.type === 'Polygon' ? [geom.coordinates] : geom.coordinates;
        polygons.forEach(function(polygon) {
            ctx.beginPath();
            polygon.forEach(function(ring) {
                ring.forEach(function(c, i) {
                    var x = (c[0] + 180) / 360 * W;
                    var y = (90 - c[1]) / 180 * H;
                    if (i === 0) ctx.moveTo(x, y);
                    else ctx.lineTo(x, y);
                });
                ctx.closePath();
            });
            ctx.fill('evenodd');
        });
    });

    var texture = new THREE.CanvasTexture(canvas);
    var landGeo = new THREE.SphereGeometry(LAND_RADIUS, 64, 64);
    var landMat = new THREE.MeshBasicMaterial({ map: texture, transparent: true });
    world.add(new THREE.Mesh(landGeo, landMat));
}

function buildBorders(geojson) {
    var mat = new THREE.LineBasicMaterial({
        color: hexToInt('${config.borderColor}'),
        transparent: true,
        opacity: 0.5
    });
    geojson.features.forEach(function(feature) {
        var geom = feature.geometry;
        if (!geom) return;
        var polygons = geom.type === 'Polygon' ? [geom.coordinates] : geom.coordinates;
        polygons.forEach(function(polygon) {
            polygon.forEach(function(ring) {
                var pts = ring.map(function(c) { return latLngTo3D(c[1], c[0], 1.002); });
                var geo = new THREE.BufferGeometry().setFromPoints(pts);
                world.add(new THREE.Line(geo, mat));
            });
        });
    });
}

function makeTextSprite(text, color) {
    var canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 80;
    var ctx = canvas.getContext('2d');
    ctx.font = 'bold 32px Arial, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    var metrics = ctx.measureText(text);
    var pw = metrics.width + 24, ph = 44;
    var px = 256 - pw / 2, py = 18;
    ctx.fillStyle = 'rgba(2,11,24,0.72)';
    ctx.beginPath();
    ctx.roundRect(px, py, pw, ph, 6);
    ctx.fill();
    ctx.fillStyle = color || '#ffffff';
    ctx.fillText(text, 256, 40);
    var tex = new THREE.CanvasTexture(canvas);
    var mat = new THREE.SpriteMaterial({ map: tex, transparent: true, depthTest: false });
    var sprite = new THREE.Sprite(mat);
    sprite.scale.set(0.32, 0.05, 1.0);
    return sprite;
}

function addMarker(m) {
    var group = new THREE.Group();
    var pos = latLngTo3D(m.lat, m.lng, 1.012);
    group.position.copy(pos);

    // Orient marker outward from globe center
    group.lookAt(new THREE.Vector3(0, 0, 0));
    group.rotateX(Math.PI / 2);

    var dot, innerRing, outerRing;

    if (m.style === 'current') {
        var dotGeo = new THREE.SphereGeometry(DOT_RADIUS_CURRENT, 16, 16);
        var dotMat = new THREE.MeshBasicMaterial({ color: hexToInt('${config.currentDotColor}') });
        dot = new THREE.Mesh(dotGeo, dotMat);
        group.add(dot);

        var innerGeo = new THREE.SphereGeometry(CURRENT_RING_INNER_RADIUS, 16, 16);
        var innerMat = new THREE.MeshBasicMaterial({
            color: hexToInt('${config.currentDotColor}'),
            transparent: true,
            opacity: 0.24
        });
        innerRing = new THREE.Mesh(innerGeo, innerMat);
        group.add(innerRing);

        var outerGeo = new THREE.SphereGeometry(CURRENT_RING_OUTER_RADIUS, 16, 16);
        var outerMat = new THREE.MeshBasicMaterial({
            color: hexToInt('${config.currentDotColor}'),
            transparent: true,
            opacity: 0.10
        });
        outerRing = new THREE.Mesh(outerGeo, outerMat);
        group.add(outerRing);

        group.userData.type = 'current';
        group.userData.innerRing = innerRing;
        group.userData.outerRing = outerRing;
    } else if (m.style === 'destination') {
        var dotGeo = new THREE.SphereGeometry(DOT_RADIUS_DEFAULT, 16, 16);
        var dotMat = new THREE.MeshBasicMaterial({ color: hexToInt('${config.destinationDotColor}') });
        dot = new THREE.Mesh(dotGeo, dotMat);
        group.add(dot);
        group.userData.type = 'destination';
    } else if (m.style === 'custom') {
        var sz = (m.size || 1) * DOT_RADIUS_DEFAULT;
        var dotGeo = new THREE.SphereGeometry(sz, 16, 16);
        var dotMat = new THREE.MeshBasicMaterial({ color: hexToInt(m.colorHex || '#ffffff') });
        dot = new THREE.Mesh(dotGeo, dotMat);
        group.add(dot);
        group.userData.type = m.pulse ? 'current' : 'default';
    } else {
        var dotGeo = new THREE.SphereGeometry(DOT_RADIUS_DEFAULT, 16, 16);
        var dotMat = new THREE.MeshBasicMaterial({ color: 0xffffff });
        dot = new THREE.Mesh(dotGeo, dotMat);
        group.add(dot);
        group.userData.type = 'default';
    }

    group.userData.markerId = m.id;
    group.userData.dot = dot;

    if (m.label) {
        var labelColor = m.style === 'current' ? '${config.currentDotColor}' : '${config.destinationDotColor}';
        var sprite = makeTextSprite(m.label, labelColor);
        var labelPos = latLngTo3D(m.lat, m.lng, 1.09);
        sprite.position.copy(labelPos);
        world.add(sprite);
        group.userData.labelSprite = sprite;
    }

    world.add(group);
    markers[m.id] = group;
}

function removeMarker(id) {
    var g = markers[id];
    if (g) {
        if (g.userData.labelSprite) world.remove(g.userData.labelSprite);
        world.remove(g);
        delete markers[id];
    }
}

function arcCurvePoints(a) {
    var A = latLngTo3D(a.fromLat, a.fromLng, 1.0);
    var B = latLngTo3D(a.toLat,   a.toLng,   1.0);
    var ctrl = A.clone().add(B).multiplyScalar(0.5).normalize().multiplyScalar(1.48);
    var curve = new THREE.QuadraticBezierCurve3(A, ctrl, B);
    return curve.getPoints(100);
}

function buildDashedArc(a) {
    var points = arcCurvePoints(a);
    var geometry = new THREE.BufferGeometry().setFromPoints(points);
    var material = new THREE.LineDashedMaterial({
        color: hexToInt('${config.arcColor}'),
        dashSize: 0.06,
        gapSize: 0.04,
        transparent: true,
        opacity: 0.45
    });
    var line = new THREE.Line(geometry, material);
    line.computeLineDistances();
    return line;
}

function buildTubeArc(a, style) {
    var points = arcCurvePoints(a);
    var catmull = new THREE.CatmullRomCurve3(points);

    var colorHex = '${config.arcColor}';
    var widthScale = 1.0;
    var opacity = 0.75;
    if (style === 'trail') {
        opacity = 0.28;
        widthScale = 0.8;
    } else if (style === 'custom') {
        colorHex = a.colorHex || colorHex;
        widthScale = (a.width !== undefined) ? a.width : 1.0;
    }

    var tubeGeo = new THREE.TubeGeometry(catmull, 100, ARC_TUBE_RADIUS * widthScale, 6, false);
    var tubeMat = new THREE.MeshBasicMaterial({
        color: hexToInt(colorHex),
        transparent: true,
        opacity: opacity
    });
    var tube = new THREE.Mesh(tubeGeo, tubeMat);

    var progress = (a.progress !== undefined) ? a.progress : 1.0;
    if (progress < 1.0) {
        var total = tubeGeo.index ? tubeGeo.index.count : tubeGeo.attributes.position.count;
        tubeGeo.setDrawRange(0, Math.floor(total * progress));
    }

    return tube;
}

function addArc(a) {
    var style = a.style || 'flight';
    var object = (style === 'dashed') ? buildDashedArc(a) : buildTubeArc(a, style);

    object.userData.arcId = a.id;
    object.userData.style = style;
    world.add(object);
    arcs[a.id] = object;

    if (style === 'flight') {
        var progress = (a.progress !== undefined) ? a.progress : 1.0;
        if (progress < 1.0) {
            arcAnimations[a.id] = { start: progress, elapsed: 0 };
        }
    }
}

function removeArc(id) {
    var mesh = arcs[id];
    if (mesh) { world.remove(mesh); delete arcs[id]; }
    delete arcAnimations[id];
}

function updateGlobe(json) {
    var data = (typeof json === 'string') ? JSON.parse(json) : json;

    if (data.rotation) {
        world.rotation.x = data.rotation.x;
        world.rotation.y = data.rotation.y;
    }

    if (data.markers) {
        var incoming = {};
        data.markers.forEach(function(m) { incoming[m.id] = m; });
        Object.keys(markers).forEach(function(id) {
            if (!incoming[id]) removeMarker(id);
        });
        data.markers.forEach(function(m) {
            if (!markers[m.id]) addMarker(m);
        });
    }

    if (data.arcs) {
        var incomingArcs = {};
        data.arcs.forEach(function(a) { incomingArcs[a.id] = a; });
        Object.keys(arcs).forEach(function(id) {
            if (!incomingArcs[id]) removeArc(id);
        });
        data.arcs.forEach(function(a) {
            var existing = arcs[a.id];
            var styleChanged = existing && existing.userData.style !== (a.style || 'flight');
            if (!existing || styleChanged) {
                if (existing) removeArc(a.id);
                addArc(a);
            }
        });
    }

    if (data.config) {
        if (data.config.autoRotate !== undefined) autoRotate = data.config.autoRotate;
        if (data.config.autoRotateSpeed !== undefined) autoRotateSpeed = data.config.autoRotateSpeed;
    }
}

function flyTo(lat, lng) {
    var targetY = -THREE.MathUtils.degToRad(lng);
    var startY = world.rotation.y % (2 * Math.PI);
    var diff = targetY - startY;
    if (diff > Math.PI)  diff -= 2 * Math.PI;
    if (diff < -Math.PI) diff += 2 * Math.PI;

    flyToAnim = {
        startY: startY,
        targetY: startY + diff,
        startX: world.rotation.x,
        targetX: Math.PI / 4 - THREE.MathUtils.degToRad(lat) * 0.15,
        startZ: camera.position.z,
        elapsed: 0
    };
}

// Cinematic flyover: instead of rotating the globe under a fixed outside camera, the
// camera itself leaves the source city and sweeps through space above the globe's surface
// along the same great-circle path as the flight arc, descending to a low hover directly
// above the destination, looking straight down the whole time (a top-down, satellite-
// flyover view rather than an orbit-and-zoom view). The sweep direction and the descent
// are animated on separate timelines (see animate()) so the camera visibly travels the
// path for the full duration instead of the altitude drop swallowing the sideways motion.
// Fires 'flightComplete' once the camera settles above the destination, so the caller can
// layer a landing effect (e.g. a destination photo) on top at the right moment.
//
// srcLat/srcLng may be null, in which case the flyover starts from wherever the camera is
// currently looking instead of an explicit source city.
function animateFlight(srcLat, srcLng, lat, lng) {
    world.updateMatrixWorld(true);
    var startDir = (srcLat !== null && srcLng !== null)
        ? latLngTo3D(srcLat, srcLng, 1.0).applyMatrix4(world.matrixWorld).normalize()
        : camera.position.clone().normalize();

    cameraFlightAnim = {
        startDir: startDir,
        startAlt: camera.position.length(),
        dstDir: latLngTo3D(lat, lng, 1.0).applyMatrix4(world.matrixWorld).normalize(),
        lat: lat,
        lng: lng,
        elapsed: 0
    };
}

var MIN_ZOOM = 2.5, MAX_ZOOM = 12.0;
var pinchStartDist = null, pinchStartZ = null;

function pinchDist(touches) {
    var dx = touches[0].clientX - touches[1].clientX;
    var dy = touches[0].clientY - touches[1].clientY;
    return Math.sqrt(dx * dx + dy * dy);
}

function setupInteraction() {
    var canvas = renderer.domElement;
    var startX, startY;

    function onPointerDown(x, y) {
        isDragging = true;
        previousMouseX = x;
        previousMouseY = y;
    }
    function onPointerMove(x, y) {
        if (!isDragging) return;
        var dx = x - previousMouseX;
        var dy = y - previousMouseY;
        world.rotation.y += dx * 0.005;
        world.rotation.x += dy * 0.005;
        world.rotation.x = Math.max(-0.7, Math.min(0.7, world.rotation.x));
        previousMouseX = x;
        previousMouseY = y;
    }
    function onPointerUp(x, y, isTap) {
        if (isTap && Math.abs(x - startX) < 5 && Math.abs(y - startY) < 5) {
            handleTap(x, y);
        }
        isDragging = false;
        try {
            Android.onEvent(JSON.stringify({
                event: 'dragEnd',
                rotationX: world.rotation.x,
                rotationY: world.rotation.y
            }));
        } catch(e) {}
    }

    // Mouse drag + wheel zoom
    canvas.addEventListener('mousedown', function(e) {
        startX = e.clientX; startY = e.clientY;
        onPointerDown(e.clientX, e.clientY);
    });
    canvas.addEventListener('mousemove', function(e) { onPointerMove(e.clientX, e.clientY); });
    canvas.addEventListener('mouseup',   function(e) { onPointerUp(e.clientX, e.clientY, true); });
    canvas.addEventListener('wheel', function(e) {
        e.preventDefault();
        camera.position.z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, camera.position.z + e.deltaY * 0.01));
    }, { passive: false });

    // Touch drag + pinch zoom
    canvas.addEventListener('touchstart', function(e) {
        e.preventDefault();
        if (e.touches.length === 2) {
            pinchStartDist = pinchDist(e.touches);
            pinchStartZ = camera.position.z;
            isDragging = false;
            return;
        }
        var t0 = e.touches[0];
        startX = t0.clientX; startY = t0.clientY;
        onPointerDown(t0.clientX, t0.clientY);
    }, { passive: false });

    canvas.addEventListener('touchmove', function(e) {
        e.preventDefault();
        if (e.touches.length === 2 && pinchStartDist !== null) {
            var scale = pinchStartDist / pinchDist(e.touches);
            camera.position.z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, pinchStartZ * scale));
            return;
        }
        if (e.touches.length === 1) {
            onPointerMove(e.touches[0].clientX, e.touches[0].clientY);
        }
    }, { passive: false });

    canvas.addEventListener('touchend', function(e) {
        e.preventDefault();
        pinchStartDist = null;
        if (e.touches.length === 0) {
            var t0 = e.changedTouches[0];
            onPointerUp(t0.clientX, t0.clientY, true);
        }
    }, { passive: false });

    window.addEventListener('resize', function() {
        var W = window.innerWidth, H = window.innerHeight;
        camera.aspect = W / H;
        camera.updateProjectionMatrix();
        renderer.setSize(W, H, false);
    });
}

function handleTap(clientX, clientY) {
    var raycaster = new THREE.Raycaster();
    var mouse = new THREE.Vector2(
        (clientX / window.innerWidth)  * 2 - 1,
       -(clientY / window.innerHeight) * 2 + 1
    );
    raycaster.setFromCamera(mouse, camera);

    var dotMeshes = [];
    Object.keys(markers).forEach(function(id) {
        var g = markers[id];
        if (g.userData.dot) dotMeshes.push(g.userData.dot);
    });

    var hits = raycaster.intersectObjects(dotMeshes);
    if (hits.length > 0) {
        var hitDot = hits[0].object;
        Object.keys(markers).forEach(function(id) {
            if (markers[id].userData.dot === hitDot) {
                try {
                    Android.onEvent(JSON.stringify({ event: 'markerTapped', markerId: id }));
                } catch(e) {}
            }
        });
    }
}

function animate() {
    requestAnimationFrame(animate);
    t += 0.016;

    if (cameraFlightAnim) {
        cameraFlightAnim.elapsed += FRAME_DT;
        var flightT = Math.min(1.0, cameraFlightAnim.elapsed / CAMERA_FLIGHT_DURATION);

        // Sweep the view direction across the great-circle path for the *entire* duration...
        var dir = slerpDirection(cameraFlightAnim.startDir, cameraFlightAnim.dstDir, easeInOut(flightT));
        // ...while the altitude drop is eased separately (stays high, then dives near the
        // end) so the sideways travel stays visible instead of being swallowed by the zoom.
        var alt = THREE.MathUtils.lerp(cameraFlightAnim.startAlt, CAMERA_FLIGHT_ALT_END, easeIn(flightT));

        camera.position.copy(dir).multiplyScalar(alt);
        camera.lookAt(dir); // straight down at the surface below

        if (cameraFlightAnim.elapsed >= CAMERA_FLIGHT_DURATION) {
            // Re-home the camera to its normal on-axis framing (destination centered) so
            // drag/autoRotate/flyTo — which all assume the camera sits fixed at (0,0,z)
            // looking down -Z — keep working correctly after this off-axis flyover.
            world.rotation.y = -THREE.MathUtils.degToRad(cameraFlightAnim.lng);
            world.rotation.x = Math.PI / 4 - THREE.MathUtils.degToRad(cameraFlightAnim.lat) * 0.15;
            camera.position.set(0, 0, ${config.cameraDistance});
            camera.rotation.set(0, 0, 0);
            cameraFlightAnim = null;
            try { Android.onEvent(JSON.stringify({ event: 'flightComplete' })); } catch(e) {}
        }
    } else if (flyToAnim) {
        flyToAnim.elapsed += FRAME_DT;
        var flyT = easeInOut(Math.min(1.0, flyToAnim.elapsed / FLY_TO_DURATION));
        world.rotation.y = THREE.MathUtils.lerp(flyToAnim.startY, flyToAnim.targetY, flyT);
        world.rotation.x = THREE.MathUtils.lerp(flyToAnim.startX, flyToAnim.targetX, flyT);

        // Pull the camera back so the arc's midpoint stays in frame mid-flight, then settle back in on arrival.
        var pullBack = Math.sin(Math.PI * flyT) * FLY_TO_ZOOM_OUT;
        camera.position.z = Math.min(MAX_ZOOM, flyToAnim.startZ + pullBack);

        if (flyToAnim.elapsed >= FLY_TO_DURATION) {
            camera.position.z = flyToAnim.startZ;
            flyToAnim = null;
            try {
                Android.onEvent(JSON.stringify({
                    event: 'dragEnd',
                    rotationX: world.rotation.x,
                    rotationY: world.rotation.y
                }));
            } catch(e) {}
        }
    } else if (autoRotate && !isDragging) {
        world.rotation.y += autoRotateSpeed;
    }

    Object.keys(arcAnimations).forEach(function(id) {
        var anim = arcAnimations[id];
        var mesh = arcs[id];
        if (!mesh || !mesh.geometry.index) { delete arcAnimations[id]; return; }
        anim.elapsed += FRAME_DT;
        var arcT = easeInOut(Math.min(1.0, anim.elapsed / ARC_ANIM_DURATION));
        var progress = anim.start + (1.0 - anim.start) * arcT;
        var total = mesh.geometry.index.count;
        mesh.geometry.setDrawRange(0, Math.floor(total * progress));
        if (anim.elapsed >= ARC_ANIM_DURATION) {
            delete arcAnimations[id];
            try { Android.onEvent(JSON.stringify({ event: 'arcAnimationComplete', arcId: id })); } catch(e) {}
        }
    });

    world.updateMatrixWorld(true);
    var camDir = camera.position.clone().normalize();

    Object.keys(markers).forEach(function(id) {
        var g = markers[id];

        // Hide markers on the far side of the globe (surface normal facing away from the camera).
        var worldNormal = g.position.clone().applyMatrix4(world.matrixWorld).normalize();
        var facingCamera = worldNormal.dot(camDir) > 0;
        g.visible = facingCamera;
        if (g.userData.labelSprite) g.userData.labelSprite.visible = facingCamera;

        if (g.userData.type === 'current' && g.userData.innerRing && g.userData.outerRing) {
            var inner = g.userData.innerRing;
            var outer = g.userData.outerRing;
            var s1 = 1 + 0.55 * Math.abs(Math.sin(t * 1.6));
            inner.scale.setScalar(s1);
            inner.material.opacity = 0.24 - 0.15 * Math.abs(Math.sin(t * 1.6));
            var s2 = 1 + 0.55 * Math.abs(Math.sin(t * 1.6 + 0.8));
            outer.scale.setScalar(s2);
            outer.material.opacity = 0.10 - 0.07 * Math.abs(Math.sin(t * 1.6 + 0.8));
        }
    });

    renderer.render(scene, camera);
}

init();
</script>
</body>
</html>
""".trimIndent()
