// The panel, as a page.
//
// Three states and one job. For the talk it waits as a clock and then a
// character stands up and greets the room; for the standby display the same
// character stands at the bottom of the panel holding a board, with the screens
// drawn over it by Android. Kotlin drives all of it through the functions hung
// on window at the bottom.
//
// One page and one bundle on purpose. The model is nineteen megabytes and the
// renderer takes a second to bring a GL surface up; a second page would pay
// both again for a character that is already loaded.
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

/**
 * Whether this is the standby display rather than the talk.
 *
 * The difference is the crop and the pose: the talk wants a face big enough to
 * watch a mouth move, and standby wants a whole figure small enough to leave
 * the top of the panel to the text it is standing under.
 */
let standby = false;
const clock = new THREE.Clock();

/**
 * How much of the figure's height fills the panel.
 *
 * 0.8 for the talk -- head and torso, since the caption covers the lower third
 * anyway and the legs were paying rent on space already spoken for. 1.8 for
 * standby, which puts the whole figure in the bottom 56% and leaves the rest
 * for the headline. Both were settled by looking at the panel; there is no
 * measurement that would have given either.
 */
const CUE_CROP = 0.8;
const STANDBY_CROP = 1.8;

/**
 * Standby draws at thirty rather than sixty.
 *
 * The talk lasts minutes and gets everything. Standby is a desk display that
 * is up for hours, and a sway and a blink do not read any differently at half
 * the rate -- while five frames a second, which is what the covered clock
 * draws at, would read as a fault. What this costs in milliamps is unmeasured;
 * that is the one open question this feature carries.
 */
const STANDBY_FRAME_MS = 1 / 30;

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
  } else if (standby) {
    if (t - lastDrawnAt < STANDBY_FRAME_MS) return;
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

    // Placed and turned every frame rather than parented and left. It is a
    // child of the hand so that it moves with the sway, but where it sits and
    // which way it faces are decided in the camera's axes -- a rig can orient
    // its bones any way it likes and the panel is not where that should be
    // discovered.
    placeBoard();
  }

  renderer.render(scene, camera);
}
frame();

// ------------------------------------------------------------- the figure ---

/** The model's own size, measured once it is in the scene. */
const figureSize = new THREE.Vector3();

/**
 * A bone on the normalized rig, which is the one three-vrm copies onto the
 * real bones on every update. Setting the real ones is undone each frame.
 */
function bone(name) {
  return vrm && vrm.humanoid && vrm.humanoid.getNormalizedBoneNode
    ? vrm.humanoid.getNormalizedBoneNode(name)
    : null;
}

/**
 * Points the camera at the model, measured after its matrices are up to date.
 *
 * Three attempts got here. A fixed camera height assumed an adult-sized figure
 * and this model stands 0.72 units tall. A bounding box read before
 * updateMatrixWorld put the camera at knee height. The head bone looked like
 * the reliable landmark and is not: this character's head sits at 0.28 of a
 * 0.72 figure, nowhere near the nine tenths a human rig would give, so
 * anything scaled from it framed a patch of hair.
 *
 * So: no proportions are assumed at all. The box says how big the figure is
 * and the crop says how much of the panel it gets. A character too small to
 * read is a worse failure than one framed loosely, and this has to work with
 * whatever model is dropped in next.
 */
function frameCamera() {
  if (!vrm) return;
  vrm.scene.updateMatrixWorld(true);

  const box = new THREE.Box3().setFromObject(vrm.scene);
  box.getSize(figureSize);
  const centre = box.getCenter(new THREE.Vector3());

  const framed = figureSize.y * (standby ? STANDBY_CROP : CUE_CROP);
  const distance = framed / 2 / Math.tan((camera.fov / 2) * (Math.PI / 180));

  // Anchored to an edge rather than to the centre, so the part that matters
  // lands where it has to whatever the proportions are: the face in the upper
  // half for the talk, the feet just above the bottom edge for standby.
  const lookY = standby
    ? box.min.y - figureSize.y * 0.04 + framed / 2
    : box.max.y + figureSize.y * 0.02 - framed / 2;

  camera.position.set(centre.x, lookY, centre.z + distance);
  camera.lookAt(centre.x, lookY, centre.z);
  camera.updateProjectionMatrix();
}

/**
 * Out of the T-pose, into whatever the mode needs.
 *
 * A VRM loads with its arms straight out because that is the pose it is rigged
 * in, and nobody greets a room like that. For the talk both arms come down.
 * For standby the right one folds up to carry the board.
 *
 * The numbers are eye-work, like BASE_YAW. A rig can put its elbow anywhere
 * and no amount of reading the specification says where this one put it.
 */
function applyPose() {
  const leftArm = bone("leftUpperArm");
  const rightArm = bone("rightUpperArm");
  const rightLower = bone("rightLowerArm");

  if (leftArm) leftArm.rotation.z = -1.15;

  // The pose follows the board and not the mode. A character standing with one
  // arm raised and nothing in it reads as a fault, and most standby screens
  // have nothing to hold up.
  if (holding()) {
    if (rightArm) rightArm.rotation.set(0, 0, 0.75);
    if (rightLower) rightLower.rotation.set(0, 0, 0.55);
  } else {
    if (rightArm) rightArm.rotation.set(0, 0, 1.15);
    if (rightLower) rightLower.rotation.set(0, 0, 0);
  }
}

/** Whether there is a board to hold. */
function holding() {
  return standby && boardText.length > 0;
}

// -------------------------------------------------------------- the board ---

/**
 * What the character holds up: a plane parented to the right hand, with a
 * canvas for its face.
 *
 * The board carries the feed name and not the headline. `HeadlinesScreen`
 * settled that argument once already -- at a size that fits on a board held by
 * a character, a headline cannot be read from across a desk -- so the board
 * says the short fixed thing and the panel keeps the long one. The board earns
 * its place by saying something the text no longer has to.
 */
const BOARD_PX_W = 512;
const BOARD_PX_H = 288;

/** Board width as a fraction of the figure's height. Eye-work, again. */
const BOARD_SCALE = 0.38;

/**
 * Where the board sits relative to the hand, as fractions of the figure's
 * height: a little above it, and well in front of the chest.
 *
 * Applied in world space each frame rather than as a local offset on the bone.
 * A rig can orient its wrist any way it likes and the first attempt put the
 * board through the character's own torso; the camera's axes are the only ones
 * this page can rely on.
 */
const BOARD_LIFT = 0.11;
const BOARD_REACH = 0.30;

let boardMesh = null;
let boardTexture = null;
let boardCanvas = null;
let boardText = "";

function makeBoard() {
  const hand = bone("rightHand");
  if (!hand) return;

  boardCanvas = document.createElement("canvas");
  boardCanvas.width = BOARD_PX_W;
  boardCanvas.height = BOARD_PX_H;

  boardTexture = new THREE.CanvasTexture(boardCanvas);
  boardTexture.colorSpace = THREE.SRGBColorSpace;

  boardMesh = new THREE.Mesh(
    new THREE.PlaneGeometry(1, BOARD_PX_H / BOARD_PX_W),
    new THREE.MeshBasicMaterial({ map: boardTexture, transparent: true }),
  );
  boardMesh.frustumCulled = false;
  // The mode and the text may both have been set before the model finished
  // loading; the board is the last thing here to exist, so it reads them.
  boardMesh.visible = holding();
  hand.add(boardMesh);

  // The normalized rig carries a scale of its own, and it is not the model's.
  // Dividing it out sizes the board against the figure rather than against
  // whatever units the bone happens to be in.
  const boneScale = new THREE.Vector3();
  hand.getWorldScale(boneScale);
  boardMesh.scale.setScalar((figureSize.y * BOARD_SCALE) / (boneScale.x || 1));

  paintBoard();
}

/**
 * Canvas 2D draws Japanese in this WebView, which is the whole reason the
 * board can carry a feed name at all.
 */
/** Just above the hand and well in front of the chest, facing the viewer. */
const boardAt = new THREE.Vector3();

function placeBoard() {
  if (!boardMesh || !boardMesh.visible || !boardMesh.parent) return;
  boardMesh.parent.getWorldPosition(boardAt);
  boardAt.y += figureSize.y * BOARD_LIFT;
  boardAt.z += figureSize.y * BOARD_REACH;
  boardMesh.position.copy(boardMesh.parent.worldToLocal(boardAt));
  boardMesh.lookAt(camera.position);
}

function paintBoard() {
  if (!boardCanvas) return;
  const ctx = boardCanvas.getContext("2d");

  ctx.clearRect(0, 0, BOARD_PX_W, BOARD_PX_H);
  ctx.fillStyle = "#f4f1e8";
  ctx.fillRect(0, 0, BOARD_PX_W, BOARD_PX_H);
  ctx.strokeStyle = "#2b2b2b";
  ctx.lineWidth = 12;
  ctx.strokeRect(6, 6, BOARD_PX_W - 12, BOARD_PX_H - 12);

  ctx.fillStyle = "#1b1b1b";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";

  const laid = layOut(ctx);
  const step = laid.size * 1.18;
  const top = BOARD_PX_H / 2 - (step * (laid.lines.length - 1)) / 2;
  laid.lines.forEach((line, i) => ctx.fillText(line, BOARD_PX_W / 2, top + step * i));

  boardTexture.needsUpdate = true;
}

const BOARD_MARGIN = 56;

/** The largest the board will draw, and the smallest worth drawing. */
const BOARD_MAX_PX = 108;
const BOARD_MIN_PX = 24;

/** Where a feed title may be broken. Japanese offers nothing else. */
const BREAKS = "・･ 　/|:：-—";

/**
 * The board is a label, and a label has to be read from across a desk.
 *
 * Feed titles run from "ギズモード・ジャパン" to "はてなブックマーク - 人気
 * エントリー - 総合". On one line the long ones shrink to something nobody
 * reads, so a name is broken over two lines and then three before the size is
 * given up. A line is only taken if it actually buys size back.
 *
 * Trimming the title to its first segment was the other way out and is worse:
 * there is no rule that keeps "ギズモード・ジャパン" whole and still cuts
 * "はてなブックマーク - 人気エントリー - 総合" down, and a board naming the
 * wrong thing is worse than a board naming it in small type.
 */
function layOut(ctx) {
  const font = (n) => `700 ${n}px system-ui, "Noto Sans JP", sans-serif`;
  const room = BOARD_PX_W - BOARD_MARGIN;

  const fit = (lines) => {
    let size = BOARD_MAX_PX;
    ctx.font = font(size);
    const widest = () => lines.reduce((w, l) => Math.max(w, ctx.measureText(l).width), 0);
    while (size > BOARD_MIN_PX && widest() > room) {
      size -= 4;
      ctx.font = font(size);
    }
    return size;
  };

  let lines = [boardText];
  let size = fit(lines);

  // Two thirds of the biggest the board can draw. Above that the name is
  // already legible and a second line would only make it smaller.
  for (let n = 2; n <= 3 && size < BOARD_MAX_PX * 0.66; n += 1) {
    const candidate = splitInto(boardText, n);
    if (candidate.length < n) break;
    const candidateSize = fit(candidate);
    if (candidateSize <= size) break;
    lines = candidate;
    size = candidateSize;
  }

  ctx.font = font(size);
  return { lines: lines, size: size };
}

/**
 * Cuts [text] into [n] lines at the separators nearest the even divisions, or
 * at the divisions themselves where the writing offers no seam.
 */
function splitInto(text, n) {
  if (text.length < n * 2) return [text];

  const cuts = [];
  for (let k = 1; k < n; k += 1) {
    const ideal = (text.length * k) / n;
    let at = -1;
    for (let i = 1; i < text.length; i += 1) {
      if (cuts.indexOf(i) >= 0) continue;
      if (BREAKS.indexOf(text[i]) < 0) continue;
      if (at < 0 || Math.abs(i - ideal) < Math.abs(at - ideal)) at = i;
    }
    cuts.push(at >= 0 ? at : Math.round(ideal));
  }
  cuts.sort((a, b) => a - b);

  const lines = [];
  let from = 0;
  for (let i = 0; i < cuts.length; i += 1) {
    lines.push(text.slice(from, cuts[i]));
    // Step over the separator itself; a line ending in a dash names nothing
    // that the dash was joining.
    from = BREAKS.indexOf(text[cuts[i]]) >= 0 ? cuts[i] + 1 : cuts[i];
  }
  lines.push(text.slice(from));

  return lines
    .map((line) => {
      let a = 0;
      let b = line.length;
      while (a < b && BREAKS.indexOf(line[a]) >= 0) a += 1;
      while (b > a && BREAKS.indexOf(line[b - 1]) >= 0) b -= 1;
      return line.slice(a, b);
    })
    .filter((line) => line.length > 0);
}

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

    // Framed and posed from the model, both of which depend on the mode.
    // Done here and again from stageStandby: the standby display switches mode
    // after the model is already up, and re-framing is a few matrix operations
    // against re-loading nineteen megabytes.
    frameCamera();
    applyPose();
    makeBoard();

    const round = (v) => v.toArray().map((n) => Math.round(n * 100) / 100);
    report(
      "ready:" +
        JSON.stringify({
          view: [document.documentElement.clientWidth, document.documentElement.clientHeight],
          size: round(figureSize),
          cam: round(camera.position),
          meta: (vrm.meta && vrm.meta.metaVersion) || "?",
        }),
    );
  },
  undefined,
  (error) => report("failed:" + (error && error.message ? error.message : "load")),
);

function report(state) {
  if (window.StageBridge && window.StageBridge.onState) window.StageBridge.onState(state);
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

/**
 * The standby display, which is the talk's states turned inside out.
 *
 * The clock here belongs to Android -- `ClockScreen` draws it, over this page,
 * along with every other standby screen. So the page's own clock goes away
 * entirely rather than being lifted like a lid, and the character is on from
 * the moment the screen opens instead of arriving at a cue.
 *
 * Safe to call before the model has loaded: the mode is a flag, and the loader
 * reads it when it frames and poses.
 */
window.stageStandby = function () {
  standby = true;
  revealed = true;
  speaking = false;
  clockEl.style.display = "none";
  bubbleEl.style.display = "none";
  frameCamera();
  if (boardMesh) boardMesh.visible = holding();
  applyPose();
  return "standby";
};

/**
 * What the board says, or an empty string to put it away.
 *
 * Called whenever the screen above changes what it is showing. Most standby
 * screens have nothing for it, and a character holding a blank board looks
 * like a character holding a mistake -- so an empty string hides the mesh
 * rather than drawing an empty one.
 */
window.stageBoard = function (text) {
  boardText = typeof text === "string" ? text.trim() : "";
  if (boardMesh) {
    boardMesh.visible = holding();
    if (boardMesh.visible) paintBoard();
  }
  // The arm comes up with the board and goes down with it.
  applyPose();
  return boardText;
};

window.addEventListener("resize", resize);

window.cueIdle();
