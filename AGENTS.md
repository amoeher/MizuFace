# AGENTS.md

## Project Snapshot
- Android app (`:app`) for real-time face tracking and VTuber streaming over UDP (`com.amoherom.mizuface`).
- Primary runtime path is `MainActivity` -> Navigation graph -> `VtuberPCFragment` (`app/src/main/res/navigation/nav_graph.xml`).
- Core stack: CameraX + MediaPipe Face Landmarker + manual JSON/UDP transport.

## Architecture That Matters
- `app/src/main/java/com/amoherom/mizuface/fragment/VtuberPCFragment.kt` is the orchestration hub (camera setup, inference callbacks, UI updates, network send, prefs).
- `app/src/main/java/com/amoherom/mizuface/FaceLandmarkerHelper.kt` wraps MediaPipe setup/inference and owns `RunningMode` constraints and delegate selection.
- `app/src/main/java/com/amoherom/mizuface/UDPListner.kt` listens for tracker discovery broadcasts (`"iOSTrackingDataRequest"`) on port `21412`.
- `app/src/main/java/com/amoherom/mizuface/CameraFov.kt` converts camera metadata into FOV math used for face position estimation.
- `MainViewModel` stores landmarker thresholds/delegate across fragment recreation; no repository/data layer exists.

## End-to-End Data Flow
- Camera frames: `ImageAnalysis` (`OUTPUT_IMAGE_FORMAT_RGBA_8888`) -> `detectFace()` -> `FaceLandmarkerHelper.detectLiveStream()`.
- Inference callback: `VtuberPCFragment.onResults()` computes rotation/position/eye vectors from landmarks + iris points.
- Blendshape names come from `BlenshapeMapper.blendshapeBundle`; weights are multiplied by per-shape UI values before sending.
- Payload format: `buildTrackerFaceJson()` emits compact JSON with keys `Timestamp`, `Rotation`, `Position`, `EyeLeft`, `EyeRight`, `BlendShapes`.
- Transport: `DatagramSocket` sends to `PC_IP:PC_PORT` (default port string initialized as `50509`).

## Project-Specific Conventions
- Preference storage is activity-scoped via `activity?.getPreferences(Context.MODE_PRIVATE)` in `VtuberPCFragment`; keys like `pc_ip`, `pc_port`, `cam_cover_id`, and each blendshape name.
- UI blendshape rows are created dynamically by inflating `app/src/main/res/layout/blendshape_row.xml` into `blendshapeList`.
- Navigation fallback pattern: camera permission checks redirect to `PermissionsFragment` both on `onViewCreated` and `onResume`.
- Naming is inconsistent in legacy code (`BlenshapeMapper`, `UDPListner`, `InitiatePCConnection`); preserve existing names unless refactoring broadly.

## Build/Test/Debug Workflows
- Windows wrapper is present: `gradlew.bat`; standard Android/Gradle project layout.
- Verified in this environment: Gradle cannot run until Java is configured (`JAVA_HOME` / `java` missing).
- After Java setup, start with:
  - `./gradlew.bat :app:assembleDebug`
  - `./gradlew.bat :app:testDebugUnitTest`
  - `./gradlew.bat :app:connectedDebugAndroidTest` (device/emulator required)

## Integration Notes
- Model asset path is fixed to `face_landmarker.task` (loaded by `FaceLandmarkerHelper` from app assets).
- Camera lens switching toggles front/back and recomputes FOV before rebinding use cases.
- Discovery and streaming are UDP-based; keep protocol compatibility with VSeeFace/VNyan JSON expectations when editing payload keys or units.
- Manifest currently requests `CAMERA` and `ACCESS_WIFI_STATE`; permission UI only requests camera at runtime.

