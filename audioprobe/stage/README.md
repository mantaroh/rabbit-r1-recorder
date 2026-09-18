# stage — the page the panel draws a character on

Source for `audioprobe/src/main/assets/stage/bundle.js`. Kept outside `assets/`
so the unbundled module is not packaged into the APK as well.

Two screens use it and it has a mode for each. `CueActivity` drives
`cueSpeak` / `cueQuiet` / `cueIdle`: the page is a clock until the cue, and then
the lid comes off and the character delivers the line. `SignageActivity` drives
`stageStandby`: the page's own clock goes away entirely and the character is
framed small and low so the standby screens keep the top of the panel. One page
and one bundle, because a second of either would pay for the model and the GL
surface twice.

```bash
npm install    # versions are pinned in package.json
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

## The motions

`assets/stage/motion/VRMA_*.vrma` are **not committed either**, and this time
the terms say so outright: the pack forbids redistributing the motions, altered
or not, "in a state where they can be extracted without permission", and a
public repository is exactly that state. Drop the files in at that path before
building; without them the character still stands and sways, and a tap reports
a failure instead of moving.

Six of the seven clips in pixiv's VRMA_MotionPack are used, listed in `MOTIONS`
in `entry.js`. `VRMA_04` is drawing and firing a weapon and is left out. The
credit the terms name for commercial use — キャラクターアニメーション:
ピクシブ株式会社 VRoidプロジェクト — belongs on the licence screen in 設定 when
that exists, not on the panel. Terms:
<https://www.pixiv.net/support?type=45&mode=inquiry&service=vroid-feedback>

They are read only in standby, one at a time, and only after the panel enters
that mode: the talk cannot play one and has no business reading five megabytes
it will never use.

Nothing about a model's scale, proportions or facing is assumed; the camera is
framed from the bounding box after the matrices are updated. The numbers decided by looking at the panel
rather than measured are named and gathered near the top of `entry.js`:
`BASE_YAW`, which is there because `VRMUtils.rotateVRM0` did not leave this
model facing the room, and the crops, which are where they are because that is
where they looked right on a 240 dp panel.

`STANDBY_CROP` has a counterpart in Kotlin — `SignageActivity.CONTENT_FRACTION`
— and the two have to agree about where the character stops and the text
starts. Nothing enforces it.
