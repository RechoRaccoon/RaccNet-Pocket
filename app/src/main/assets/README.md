Model files needed by VRM mode's tracking pipeline (util/*LandmarkerHelper.kt).
All three are Apache 2.0 licensed, from Google's MediaPipe project — free,
no royalties, no runtime attribution required.

- face_landmarker.task      — present.
- hand_landmarker.task      — present.
- pose_landmarker_full.task — present.
  (pose_landmarker_lite is a smaller/faster alternative, same URL pattern
  as above, worth trying if _full runs too slow on your test device:
  https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task)

All three are now bundled, so face, hands, and pose/body should all report
live tracking in VrmModeScreen's debug overlay (not the "no landmarker
output yet" placeholder text). Missing files still fail soft either way —
that helper's create() logs the failure and returns null; the rest of the
pipeline keeps working.
