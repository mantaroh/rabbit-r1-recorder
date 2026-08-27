# stage — the page the panel shows during the talk

Source for `audioprobe/src/main/assets/stage/bundle.js`. Kept outside `assets/`
so the unbundled module is not packaged into the APK as well.

```bash
npm install three@0.169.0 @pixiv/three-vrm@3.1.6
npx esbuild entry.js --bundle --format=iife --minify --target=chrome110 \
  --outfile=../src/main/assets/stage/bundle.js
```

Bundled to one classic script on purpose. Assets are served to the WebView
through `shouldInterceptRequest` under a host that does not exist, which gives
the page an ordinary origin without adding androidx.webkit — and a single
`<script src>` needs no import map to go with it.

## The model

`assets/stage/character.vrm` is **not committed**: it is a third-party model of
a dozen megabytes that will be swapped for whichever character the talk
actually wants. Drop a `.vrm` in at that path before building.

Without it the page reports a failure, `CueActivity` keeps its own views, and
the line is still spoken — the audio never goes through the page. So a
forgotten model costs the picture and nothing else.

The model in use for this talk is ミニずんだもん (SSS LLC., 2025), whose own
metadata carries `creditNotation: required`. The credit is on the panel, in
`index.html`, under the caption. It is not decoration and it does not come off.
Terms: <https://zunko.jp/guideline.html>.

Not committed for the same reason it is not ours to commit.

Nothing about a model's scale, proportions or facing is assumed; the camera is
framed from the bounding box after the matrices are updated. `BASE_YAW` in
`entry.js` is the one thing decided by looking at the panel rather than
measured, and it is there because `VRMUtils.rotateVRM0` did not leave this
model facing the room.
