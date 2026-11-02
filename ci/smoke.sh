#!/usr/bin/env bash
# Emulator smoke test: install the debug APK, let it detect capabilities, then transcode a clip
# with several presets through the real app (UI -> queue -> service -> FFmpegKit -> MediaStore).
set -uo pipefail
PKG=app.recode.debug
APK=$(ls dist/*-debug.apk | head -1)
OUT=smoke-out
mkdir -p "$OUT"
fail() { echo "SMOKE FAILED: $*"; collect; exit 1; }
collect() {
  adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>/dev/null || true
  for f in logs/app.log logs/probe.log logs/last_crash.txt capabilities.json jobs.json; do
    adb shell run-as $PKG cat "files/$f" > "$OUT/$(basename $f)" 2>/dev/null || true
  done
  echo "----- probe.log (tail) -----"; tail -40 "$OUT/probe.log" 2>/dev/null
  echo "----- app.log (tail) -----"; tail -60 "$OUT/app.log" 2>/dev/null
  echo "----- crashes -----"; grep -A25 -E "FATAL EXCEPTION|>>> $PKG" "$OUT/logcat.txt" | head -120
  echo "----- crash buffer -----"; adb logcat -d -b crash -v threadtime 2>/dev/null | tail -80
  echo "----- relevant logcat -----"
  grep -iE "ffmpeg|probe|recode|nativebridge|native_bridge|ndk_translation|houdini|linker|crash_dump|DEBUG  |lowmemorykiller|ActivityManager: (Process|Killing|Start proc)" "$OUT/logcat.txt" | tail -200
  echo "----- exit info -----"; adb shell dumpsys activity exit-info $PKG 2>/dev/null | head -40
}

adb shell getprop ro.product.cpu.abilist
adb install -r -g "$APK" || fail "install"
adb logcat -c
adb shell am start -W -n $PKG/app.recode.ui.MainActivity || fail "launch"

echo "waiting for capability probe..."
for i in $(seq 1 150); do
  adb shell run-as $PKG test -f files/capabilities.key && break
  sleep 2
done
adb shell run-as $PKG test -f files/capabilities.key || fail "capability probe never finished"
adb shell run-as $PKG cat files/capabilities.json > "$OUT/capabilities.json"
python3 - "$OUT/capabilities.json" <<'PY' || fail "capabilities look wrong"
import json, sys
c = json.load(open(sys.argv[1]))
print("ffmpeg", c["ffmpegVersion"], "verified", c["verified"], "tenBit", c["tenBit"])
assert "libx264" in c["encoders"], "libx264 missing from encoder list"
assert c["verified"].get("X264") is True, "x264 test encode failed"
PY
adb shell pidof $PKG >/dev/null || fail "app died during probe"

# Test clip, copied into the app's private files and opened via file:// (debug build, run-as).
adb push smoke.mp4 /data/local/tmp/smoke.mp4 >/dev/null
adb shell chmod 644 /data/local/tmp/smoke.mp4
adb shell run-as $PKG cp /data/local/tmp/smoke.mp4 files/smoke.mp4 || fail "copy clip"
FILE=$(adb shell run-as $PKG sh -c 'cd files && pwd' | tr -d '\r')/smoke.mp4
PRESETS="hw-hevc-1080,hw-h264-1080,x264-share,x265-quality,remux,audio-aac,audio-flac"
adb shell am start -n $PKG/app.recode.ui.MainActivity -a android.intent.action.VIEW -d "file://$FILE" --es smoke_presets "$PRESETS" || fail "open clip"

echo "waiting for jobs..."
for i in $(seq 1 180); do
  sleep 3
  adb shell run-as $PKG cat files/jobs.json > "$OUT/jobs.json" 2>/dev/null || continue
  python3 - "$OUT/jobs.json" <<'PY' && break
import json, sys
jobs = json.load(open(sys.argv[1]))
active = [j for j in jobs if j["status"] in ("QUEUED", "RUNNING")]
sys.exit(0 if jobs and not active else 1)
PY
done
adb shell pidof $PKG >/dev/null || fail "app died while encoding"
python3 - "$OUT/jobs.json" <<'PY' || fail "some jobs did not finish successfully"
import json, sys
jobs = json.load(open(sys.argv[1]))
print(f"{len(jobs)} jobs")
bad = 0
for j in jobs:
    ok = j["status"] == "DONE" and j.get("outputUri") and j.get("outputBytes", 0) > 0
    print(("OK  " if ok else "BAD ") + f'{j["presetName"]:<20} {j["status"]:<9} {j.get("outputBytes",0):>9}B {j.get("outputUri")}')
    if not ok:
        bad += 1
        print("    error:", j.get("error"))
        print("    cmd:", j.get("command"))
        print("    log:", (j.get("logTail") or "")[-1500:])
sys.exit(1 if bad or len(jobs) < 7 else 0)
PY
grep -E "FATAL EXCEPTION" -A3 <(adb logcat -d) | grep -q "Process: $PKG" && fail "a crash was logged"
collect
echo "SMOKE PASSED"
