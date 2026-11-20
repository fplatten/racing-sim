"""Re-encodes the recorded movie (MJPEG + PCM AVI) to an H.264 / AAC MP4 with Blender's built-in
FFmpeg, so no separate ffmpeg install is needed:

    blender --background --factory-startup --python blender/avi_to_mp4.py [-- in.avi out.mp4]

Defaults: docs/opening-movie.avi -> docs/opening-movie.mp4 (1600x900, 30 fps, AAC 192 kb/s).
"""
import os
import sys

import bpy

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
SRC = os.path.abspath(ARGS[0]) if len(ARGS) > 0 else os.path.join(ROOT, "docs", "opening-movie.avi")
DST = os.path.abspath(ARGS[1]) if len(ARGS) > 1 else os.path.join(ROOT, "docs", "opening-movie.mp4")

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.render.fps = 30                       # set before adding strips so the sound strip length matches
scene.render.fps_base = 1.0
scene.sequence_editor_create()
seqs = scene.sequence_editor.strips if hasattr(scene.sequence_editor, "strips") else scene.sequence_editor.sequences
movie = seqs.new_movie("video", SRC, channel=1, frame_start=1)
sound = seqs.new_sound("audio", SRC, channel=2, frame_start=1)
print("movie frames", movie.frame_duration, "sound frames", sound.frame_duration)

scene.render.resolution_x = movie.elements[0].orig_width if movie.elements else 1600
scene.render.resolution_y = movie.elements[0].orig_height if movie.elements else 900
scene.render.resolution_percentage = 100
scene.frame_start = 1
scene.frame_end = movie.frame_duration

r = scene.render
if hasattr(r.image_settings, "media_type"):  # Blender 5: video output is a separate media type
    r.image_settings.media_type = "VIDEO"
r.image_settings.file_format = "FFMPEG"
r.ffmpeg.format = "MPEG4"
r.ffmpeg.codec = "H264"
r.ffmpeg.constant_rate_factor = "HIGH"
r.ffmpeg.ffmpeg_preset = "GOOD"
r.ffmpeg.gopsize = 30
r.ffmpeg.audio_codec = "AAC"
r.ffmpeg.audio_channels = "STEREO"
r.ffmpeg.audio_mixrate = 44100
r.ffmpeg.audio_bitrate = 192
r.use_sequencer = True
r.filepath = DST
r.use_file_extension = False
bpy.ops.render.render(animation=True)
print("MP4 DONE", DST)
