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

    private fun evaluateJs(json: String) {
        webView.post {
            webView.evaluateJavascript("if(typeof updateGlobe==='function')updateGlobe($json)", null)
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

    // Country borders (async fetch from assets)
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
        var dotGeo = new THREE.SphereGeometry(0.024, 16, 16);
        var dotMat = new THREE.MeshBasicMaterial({ color: hexToInt('${config.currentDotColor}') });
        dot = new THREE.Mesh(dotGeo, dotMat);
        group.add(dot);

        var innerGeo = new THREE.SphereGeometry(0.052, 16, 16);
        var innerMat = new THREE.MeshBasicMaterial({
            color: hexToInt('${config.currentDotColor}'),
            transparent: true,
            opacity: 0.24
        });
        innerRing = new THREE.Mesh(innerGeo, innerMat);
        group.add(innerRing);

        var outerGeo = new THREE.SphereGeometry(0.075, 16, 16);
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
        var dotGeo = new THREE.SphereGeometry(0.017, 16, 16);
        var dotMat = new THREE.MeshBasicMaterial({ color: hexToInt('${config.destinationDotColor}') });
        dot = new THREE.Mesh(dotGeo, dotMat);
        group.add(dot);
        group.userData.type = 'destination';
    } else if (m.style === 'custom') {
        var sz = (m.size || 1) * 0.017;
        var dotGeo = new THREE.SphereGeometry(sz, 16, 16);
        var dotMat = new THREE.MeshBasicMaterial({ color: hexToInt(m.colorHex || '#ffffff') });
        dot = new THREE.Mesh(dotGeo, dotMat);
        group.add(dot);
        group.userData.type = m.pulse ? 'current' : 'default';
    } else {
        var dotGeo = new THREE.SphereGeometry(0.017, 16, 16);
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

function addArc(a) {
    var A = latLngTo3D(a.fromLat, a.fromLng, 1.0);
    var B = latLngTo3D(a.toLat,   a.toLng,   1.0);
    var ctrl = A.clone().add(B).multiplyScalar(0.5).normalize().multiplyScalar(1.48);
    var curve = new THREE.QuadraticBezierCurve3(A, ctrl, B);
    var points = curve.getPoints(100);
    var catmull = new THREE.CatmullRomCurve3(points);
    var tubeGeo = new THREE.TubeGeometry(catmull, 100, 0.005, 6, false);
    var tubeMat = new THREE.MeshBasicMaterial({
        color: hexToInt('${config.arcColor}'),
        transparent: true,
        opacity: 0.75
    });
    var tube = new THREE.Mesh(tubeGeo, tubeMat);

    var progress = (a.progress !== undefined) ? a.progress : 1.0;
    if (progress < 1.0) {
        var total = tubeGeo.index ? tubeGeo.index.count : tubeGeo.attributes.position.count;
        tubeGeo.setDrawRange(0, Math.floor(total * progress));
    }

    tube.userData.arcId = a.id;
    world.add(tube);
    arcs[a.id] = tube;
}

function removeArc(id) {
    var mesh = arcs[id];
    if (mesh) { world.remove(mesh); delete arcs[id]; }
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
            if (!arcs[a.id]) addArc(a);
        });
    }

    if (data.config) {
        if (data.config.autoRotate !== undefined) autoRotate = data.config.autoRotate;
        if (data.config.autoRotateSpeed !== undefined) autoRotateSpeed = data.config.autoRotateSpeed;
    }
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

    if (autoRotate && !isDragging) {
        world.rotation.y += autoRotateSpeed;
    }

    Object.keys(markers).forEach(function(id) {
        var g = markers[id];
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
