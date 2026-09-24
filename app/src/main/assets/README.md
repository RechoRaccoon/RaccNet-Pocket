Model files needed by VRM mode's tracking pipeline (util/*LandmarkerHelper.kt).
All three are Apache 2.0 licensed, from Google's MediaPipe project — free,
no royalties, no runtime attribution required.

- face_landmarker.task  — present.
- hand_landmarker.task  — NOT present yet. Download from:
  https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task
- pose_landmarker_full.task — NOT present yet. Download from:
  https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task
  (pose_landmarker_lite is a smaller/faster alternative, same URL pattern,
  worth trying if _full runs too slow on your test device.)

Drop each downloaded file directly in this folder, matching the exact
filename above. Missing files fail soft — that helper's create() logs the
failure and returns null; the rest of the pipeline keeps working.
