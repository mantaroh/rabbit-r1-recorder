// The panel, as a page.
//
// Two states and one job. While it waits it is a clock; when the cue arrives a
// character stands up and greets the room. Kotlin drives both through the two
// functions hung on window at the bottom.
//
// Nothing here plays audio. The sound is native, compiled into the APK, and it
// must not depend on whether a browser engine started -- see CueActivity.

import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import { VRMLoaderPlugin, VRMUtils } from "@pixiv/three-vrm";

const clockEl = document.getElementById("clock");
const stageEl = document.getElementById("stage");
const bubbleEl = document.getElementById("bubble");
const canvas = document.getElementById("canvas");

// ------------------------------------------------------------------ clock ---

function paintClock() {
  const now = new Date();
  clockEl.textContent = now.getHours() + ":" + String(now.getMinutes()).padStart(2, "0");
}
paintClock();
setInterval(paintClock, 1000);

// ------------------------------------------------------------------ scene ---

const renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: false });
renderer.setPixelRatio(1); // The panel is 480x640; there is nothing to gain above 1.
renderer.outputColorSpace = THREE.SRGBColorSpace;

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(22, 1, 0.01, 20);

/**
 * The drawing buffer, from the element rather than from the window.
 *
 * `window.innerWidth` is zero while the page's own view is not laid out, and
 * this page is loaded before it is shown on purpose -- the model has to be in
 * memory well before the cue. Reading the document element instead means the
 * first measurement is taken whenever it is valid, and the observer below
 * takes it again if it changes.
 */
function resize() {
  const w = document.documentElement.clientWidth || window.innerWidth;
  const h = document.documentElement.clientHeight || window.innerHeight;
  if (!w || !h) return;
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
  renderer.setSize(w, h, false);
}
resize();
new ResizeObserver(resize).observe(document.documentElement);

// Flat and bright rather than modelled. VRM toon materials do their own
// shading and a dim room reads as a fault on a small panel.
scene.add(new THREE.AmbientLight(0xffffff, 1.5));
const key = new THREE.DirectionalLight(0xffffff, 1.1);
key.position.set(0.6, 1.6, 1.4);
scene.add(key);

let vrm = null;

/** Whether the mouth is moving. */
let speaking = false;

/**
 * Whether the lid is off.
 *
 * One way only. The character arrives with the last line of the talk and then
 * stays for whatever follows it -- questions, applause, somebody walking up to
 * look at the device. Going back to a clock would take the ending away again.
 */
let revealed = false;
const clock = new THREE.Clock();

/**
 * Which way the model has to be turned to face the room, decided per model.
 *
 * A VRM 1.0 character is authored facing the viewer and needs nothing. A 0.x
 * one is authored facing away and `VRMUtils.rotateVRM0` turns it -- except
 * that the 0.x model this was first built against came out facing away even
 * so, and a character delivering the last line of a talk with its back to the
 * audience is not a subtle bug. Half a turn for 0.x, none for 1.0; both
 * confirmed by looking at the panel rather than at the specification.
 */
let baseYaw = 0;

// -------------------------------------------------------------- expression ---

/** Blink on a rhythm that is not quite regular, because a metronome reads as dead. */
let nextBlinkAt = 1.5;
let blinkStartedAt = -1;

function blink(t, expressions) {
  if (blinkStartedAt < 0 && t >= nextBlinkAt) blinkStartedAt = t;
  if (blinkStartedAt < 0) return;

  const dt = t - blinkStartedAt;
  const BLINK_MS = 0.12;
  if (dt > BLINK_MS) {
    expressions.setValue("blink", 0);
    blinkStartedAt = -1;
    nextBlinkAt = t + 2 + Math.random() * 3;
    return;
  }
  // Closes and opens over the same span; a triangle is enough at this size.
  expressions.setValue("blink", 1 - Math.abs(dt / BLINK_MS - 0.5) * 2);
}

/**
 * A mouth that moves while the line is playing.
 *
 * Not lip sync. The audio is played natively and this page never sees it, so
 * the mouth is driven by a shaped oscillator: fast enough to read as speech,
 * irregular enough not to read as a machine.
 */
function mouth(t, expressions) {
  if (!speaking) {
    expressions.setValue("aa", 0);
    return;
  }
  const open = (Math.sin(t * 17) * 0.5 + 0.5) * (Math.sin(t * 6.1) * 0.25 + 0.75);
  expressions.setValue("aa", Math.max(0, open) * 0.7);
}

let lastDrawnAt = 0;

function frame() {
  requestAnimationFrame(frame);
  const delta = clock.getDelta();
  const t = clock.elapsedTime;

  // Covered by the clock and nobody is watching: draw a few times a second
  // rather than sixty, so a talk's worth of waiting is not spent turning the
  // GPU over. Still drawing, though -- that is the whole point of this page.
  // Once the lid is off this stops applying, mouth moving or not: a character
  // on screen at five frames a second reads as a fault.
  if (!revealed) {
    if (t - lastDrawnAt < 0.2) return;
    lastDrawnAt = t;
  }

  if (vrm) {
    const expressions = vrm.expressionManager;
    if (expressions) {
      blink(t, expressions);
      mouth(t, expressions);
    }
    // A slow sway around the base yaw, so a still model does not look like a
    // frozen one. Written as base plus offset rather than assigned outright,
    // which is how the first version quietly undid the turn above.
    vrm.scene.rotation.y = baseYaw + Math.sin(t * 0.5) * 0.06;
    vrm.scene.position.y = Math.sin(t * 1.1) * 0.004;
    vrm.update(delta);
  }

  renderer.render(scene, camera);
}
frame();

// ------------------------------------------------------------------- load ---

const loader = new GLTFLoader();
loader.register((parser) => new VRMLoaderPlugin(parser));

loader.load(
  "character.vrm",
  (gltf) => {
    vrm = gltf.userData.vrm;
    // VRM 0.x models face -Z; without this the character has its back to the
    // room, which is a very quiet way to fail.
    VRMUtils.rotateVRM0(vrm);
    baseYaw = vrm.meta && vrm.meta.metaVersion === "0" ? Math.PI : 0;
    VRMUtils.removeUnnecessaryJoints(gltf.scene);
    vrm.scene.traverse((object) => {
      object.frustumCulled = false;
    });
    if (vrm.lookAt) vrm.lookAt.target = camera;
    scene.add(vrm.scene);

    // Framed from the model, measured after its matrices are up to date.
    //
    // Three attempts got here. A fixed camera height assumed an adult-sized
    // figure and this model stands 0.72 units tall. A bounding box read before
    // updateMatrixWorld put the camera at knee height. The head bone looked
    // like the reliable landmark and is not: this character's head sits at
    // 0.28 of a 0.72 figure, nowhere near the nine tenths a human rig would
    // give, so anything scaled from it framed a patch of hair.
    //
    // So: no proportions are assumed at all. The whole figure is put on the
    // panel and the box says how big that is. A character too small to read
    // is a worse failure than one framed loosely, and this has to work with
    // whatever model is dropped in next.
    vrm.scene.updateMatrixWorld(true);

    const box = new THREE.Box3().setFromObject(vrm.scene);
    const size = box.getSize(new THREE.Vector3());
    const centre = box.getCenter(new THREE.Vector3());

    // Head and torso rather than the whole figure. The panel is the size of a
    // matchbox and a full-length character puts a face on it a few millimetres
    // across; the caption covers the lower third anyway, so the legs were
    // paying rent on space that is already spoken for.
    // 0.8 of the figure, not half. A tighter crop put the mouth behind the
    // caption, and the mouth is the part that moves while the line plays.
    const framed = size.y * 0.8;
    const distance = framed / 2 / Math.tan((camera.fov / 2) * (Math.PI / 180));

    // Anchored to the top of the model rather than to its centre, so the face
    // lands in the upper half whatever the proportions are -- which is the one
    // thing that has to hold when the model is swapped.
    const lookY = box.max.y + size.y * 0.02 - framed / 2;

    camera.position.set(centre.x, lookY, centre.z + distance);
    camera.lookAt(centre.x, lookY, centre.z);
    camera.updateProjectionMatrix();

    // Out of the T-pose. A VRM loads with its arms straight out because that
    // is the pose it is rigged in, and nobody greets a room like that. Set on
    // the normalized rig, which is the one three-vrm copies onto the real
    // bones on every update.
    const bone = (name) =>
      vrm.humanoid && vrm.humanoid.getNormalizedBoneNode
        ? vrm.humanoid.getNormalizedBoneNode(name)
        : null;
    const leftArm = bone("leftUpperArm");
    const rightArm = bone("rightUpperArm");
    if (leftArm) leftArm.rotation.z = -1.15;
    if (rightArm) rightArm.rotation.z = 1.15;

    const round = (v) => v.toArray().map((n) => Math.round(n * 100) / 100);
    report(
      "ready:" +
        JSON.stringify({
          view: [document.documentElement.clientWidth, document.documentElement.clientHeight],
          size: round(size),
          cam: round(camera.position),
          meta: (vrm.meta && vrm.meta.metaVersion) || "?",
        }),
    );
  },
  undefined,
  (error) => report("failed:" + (error && error.message ? error.message : "load")),
);

function report(state) {
  if (window.CueBridge && window.CueBridge.onState) window.CueBridge.onState(state);
}

// -------------------------------------------------------------- the states ---

/**
 * The character is always on the panel. The clock is what covers it.
 *
 * Showing the stage at the moment of the cue meant the compositor had a GL
 * surface to bring up right then, and it took over a second to arrive --
 * measured, by photographing the panel every 800 ms: the voice had started
 * and the picture had not. Nothing is switched on at the cue now. The canvas
 * has been drawing since the screen opened, and this only lifts the lid.
 */
window.cueSpeak = function () {
  revealed = true;
  speaking = true;
  clockEl.style.opacity = "0";
  bubbleEl.style.display = "block";
};

/** The line has finished. The mouth stops; everything else stays. */
window.cueQuiet = function () {
  speaking = false;
};

window.cueIdle = function () {
  revealed = false;
  speaking = false;
  clockEl.style.opacity = "1";
  bubbleEl.style.display = "none";
};

window.addEventListener("resize", resize);

window.cueIdle();
