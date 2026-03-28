# AGENTS.md

## Project Snapshot
- Android app (`:app`) for real-time face tracking and VTuber streaming over UDP (`com.amoherom.mizuface`).
- Primary runtime path is `MainActivity` -> Navigation graph -> `VtuberPCFragment` (`app/src/main/res/navigation/nav_graph.xml`).
- Core stack: CameraX + MediaPipe Face Landmarker + manual JSON/UDP transport.

## Architecture That Matters
- `app/src/main/java/com/amoherom/mizuface/fragment/VtuberPCFragment.kt` is the orchestration hub (camera setup, inference callbacks, UI updates, network send, prefs).
- `app/src/main/java/com/amoherom/mizuface/FaceLandmarkerHelper.kt` wraps MediaPipe setup/inference and owns `RunningMode` constraints and delegate selection.
- `app/src/main/java/com/amoherom/mizuface/UDPListner.kt` listens for tracker discovery broadcasts (`"iOSTrackingDataRequest"`) on port `21412`.
- `app/src/main/java/com/amoherom/mizuface/CameraFov.kt` converts camera metadata into FOV math used for face position estimation; also exposes `widthAtDistance(distanceCm, fov)` and `heightAtDistance(distanceCm, fov)` utilities consumed in `onResults()`.
- `MainViewModel` stores landmarker thresholds/delegate across fragment recreation; no repository/data layer exists.
- `app/src/main/java/com/amoherom/mizuface/Blendshape.kt` — enum whose members exactly mirror the keys in `BlenshapeMapper.blendshapeBundle`; not used in the live-stream path but defines the canonical shape vocabulary.
- `app/src/main/java/com/amoherom/mizuface/BlendshapeRow.kt` — data class `(blendshapeName, blendshapeProgress: ProgressBar, blendshapeWeight: EditText, blendshapeValue: Float, settings: LinearLayout, @Volatile cachedMultiplier: Float)` linking inflated UI rows to per-frame inference values. `cachedMultiplier` caches the parsed weight float to avoid per-frame `toFloatOrNull()` on the EditText string; `settings` is the collapsible row panel.
- `app/src/main/java/com/amoherom/mizuface/fragment/FaceBlendshapesResultAdapter.kt` — RecyclerView adapter powering the blendshape debug preview panel; holds a 52-slot `categories` list, sorted by descending score per frame.
- `app/src/main/java/com/amoherom/mizuface/fragment/SelectableText.kt` — custom `AppCompatTextView` subclass; overrides `setSelected()` to switch text color (`color_text_active` / `color_text_dark`) and typeface (BOLD / NORMAL). Used as the `BlendshapeSelector` and `TrackingSelector` tab labels in `fragment_vtuber_pc.xml`.
- `app/src/main/java/com/amoherom/mizuface/OverlayView.kt` is a stub (`class OverlayView` with no body); not wired into any layout or use-case.

## End-to-End Data Flow
- Camera frames: `ImageAnalysis` (`OUTPUT_IMAGE_FORMAT_RGBA_8888`) -> `detectFace()` -> `FaceLandmarkerHelper.detectLiveStream()`.
- Inference callback: `VtuberPCFragment.onResults()` computes rotation/position/eye vectors from landmarks + iris points.
- Blendshape names come from `BlenshapeMapper.blendshapeBundle`; weights are multiplied by per-shape `row.cachedMultiplier` values (not re-parsed from EditText each frame) before sending.
- Payload format: `buildTrackerFaceJson()` emits compact JSON with keys `Timestamp`, `Hotkey` (fixed `-1`), `FaceFound` (fixed `true`), `Rotation`, `Position`, `EyeLeft`, `EyeRight`, `BlendShapes`. It also accepts a `vnYanPos` parameter (always passed as `Triple(0,0,0)` currently — placeholder for future VNyan position support). Do not remove `Hotkey`/`FaceFound`; VSeeFace/VNyan expect them.
- `buildJson()` is a thin wrapper that passes through to `buildTrackerFaceJson()`; edit the latter for payload changes.
- Transport: `DatagramSocket` sends to `PC_IP:PC_PORT` (default port string initialized as `50509`).
- Default per-shape weight pref value is `"1"` (the `getPref()` fallback), meaning a 1× passthrough when no user edit has been saved.

## Project-Specific Conventions
- Preference storage is activity-scoped via `activity?.getPreferences(Context.MODE_PRIVATE)` in `VtuberPCFragment`; keys like `pc_ip`, `pc_port`, `cam_cover_id`, and each blendshape name.
- UI blendshape rows are created dynamically by inflating `app/src/main/res/layout/blendshape_row.xml` into `blendshapeList`. Each row's weight is edited **inline** via `blendshapeWeight` EditText (using `setOnEditorActionListener` for IME_ACTION_DONE and `setOnFocusChangeListener` for focus-lost); this saves to prefs and calls `InitiatePCConnection()`. Do not switch blendshape weights to an AlertDialog flow.
- IP/Port editing uses the AlertDialog + temporary EditText pattern: show dialog pre-filled with `"$PC_IP:$PC_PORT"`, validate, call `savePref()`, then call `InitiatePCConnection()`. This pattern is **only** used for IP/Port, not for blendshape weights.
- Clicking a blendshape row toggles the visibility of its `settings` LinearLayout (contains `blendshapeMin`, `blendshapeMax`, and `blendshapeWeight` EditTexts). `blendshapeMin` and `blendshapeMax` are present in `blendshape_row.xml` but are not yet read or applied in `VtuberPCFragment`.
- Navigation fallback pattern: camera permission checks redirect to `PermissionsFragment` both on `onViewCreated` and `onResume`.
- Naming is inconsistent in legacy code (`BlenshapeMapper`, `UDPListner`, `InitiatePCConnection`); preserve existing names unless refactoring broadly.
- IP:Port is entered as a single colon-separated string (`"192.168.1.x:50509"`), validated by regex `^(\d{1,3}(\.\d{1,3}){3})(\s*:\s*\d{1,5})?$` before splitting.
- `pcLinkState` ImageView cycles through four states: `search` (discovery/scanning), `connecting` (socket being opened), `link_connected` (socket open and connected), `unlink` (socket closed or error).
- Camera cover pref `cam_cover_id` stores `"1"` → animated `idlerec` drawable, `"2"` → solid `cover_solid` drawable.
- Tab navigation in `blend_shape_preview`: `BlendshapeSelector` and `TrackingSelector` are `SelectableText` views; `ShowBlendshapes()` / `ShowTrackingSettings()` set their `.isSelected` states. `hideControlsButton` (bottom `Button`) toggles visibility of the `cameraPreview` CardView and `blendShapePreview` FrameLayout entirely; it is hidden while the keyboard is open.
- Keyboard/IME insets are handled via `ViewCompat.setOnApplyWindowInsetsListener` on `binding.root`; when the keyboard opens, bottom padding adjusts and the scroll view auto-scrolls to the focused EditText.

## Build/Test/Debug Workflows
- Windows wrapper is present: `gradlew.bat`; standard Android/Gradle project layout.
- Verified in this environment: Gradle cannot run until Java is configured (`JAVA_HOME` / `java` missing).
- After Java setup, start with:
  - `./gradlew.bat :app:assembleDebug`
  - `./gradlew.bat :app:testDebugUnitTest`
  - `./gradlew.bat :app:connectedDebugAndroidTest` (device/emulator required)
- App version: `versionCode = 2` / `versionName = "2.0"`, `minSdk = 29`, `targetSdk = 35`, `compileSdk = 36`.
- Both `compose = true` and `viewBinding = true` are declared in `buildFeatures`; the entire UI is XML + ViewBinding — no Compose UI is actually rendered. Do not add Compose UI without aligning the architecture.

## Integration Notes
- Model asset path is fixed to `face_landmarker.task` (loaded by `FaceLandmarkerHelper` from app assets).
- Camera lens switching toggles front/back and recomputes FOV before rebinding use cases.
- Discovery and streaming are UDP-based; keep protocol compatibility with VSeeFace/VNyan JSON expectations when editing payload keys or units.
- Manifest currently requests `CAMERA` and `ACCESS_WIFI_STATE`; permission UI only requests camera at runtime.
- Specific MediaPipe landmark indices used in `onResults()`: nose tip = 1; left eye outer/inner/top/bottom = 33/133/159/145; right eye = 263/362/386/374; left iris = 468; right iris = 473. Face has 478 landmarks total when iris tracking is enabled.
- Hard-coded motion weights (all in `VtuberPCFragment`): `EYE_WEIGHT = 80`, `HEAD_YAW_WEIGHT = 90/π ≈ 28.6`, `HEAD_PITCH_WEIGHT = 1000/π ≈ 318.3`, `HEAD_ROLL_WEIGHT = 100/π ≈ 31.8`, `CAMERA_FOV_CM = 20f`, `ipdCm = 6.3f`. These are candidates for future global-settings exposure (see roadmap).
- `FaceLandmarkerHelper.LandmarkerListener` has `onEmpty()` with a default no-op; called when a live-stream frame yields zero face landmarks. `FaceLandmarkerHelper` also contains `detectImage()` / `detectVideoFile()` for `IMAGE`/`VIDEO` modes, but neither is invoked in the current app flow.
- `UDPListner` extracts the streaming port from the JSON `ports[0]` field of the broadcast payload (not from the discovery socket's source port); only packets containing `"iOSTrackingDataRequest"` are acted on.
- `BlenshapeMapper.getBlendshapeIndex(name: String): Int` returns the index of a shape by name in `blendshapeBundle`, or `-1` if not found; use this instead of `indexOfFirst` call-sites when looking up positions by name.
