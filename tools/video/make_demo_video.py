"""시연 영상 만들기: 구성 설명 슬라이드 + 에뮬레이터 녹화(자막·안내 음성) → 하나의 mp4.

필요: Pillow, ffmpeg(ffmpeg.exe 경로를 FFMPEG 환경 변수로), Windows 한국어 음성(Microsoft Heami).
사용: python tools/video/make_demo_video.py <녹화 폴더> <출력 폴더>
녹화 폴더에는 demoA.mp4(도착), demoB.mp4(지나침), demoC.mp4(주차) 가 있어야 한다.
자막·음성 시각은 아래 CLIPS 표에 적는다(녹화를 다시 하면 이 값만 고친다).
"""
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

RAW = Path(sys.argv[1])
OUT = Path(sys.argv[2])
FFMPEG = os.environ.get("FFMPEG", "ffmpeg")
BUILD = OUT / "build"
BUILD.mkdir(parents=True, exist_ok=True)

W, H, FPS = 1920, 1080, 30
FONT_B = "C:/Windows/Fonts/malgunbd.ttf"
FONT_R = "C:/Windows/Fonts/malgun.ttf"
# 앱과 같은 Pleos 밝은 테마 색 (ui/PleosTheme.kt). WHITE 는 이름만 남은 "본문 글자색"이다
BG = (247, 248, 250)
CARD = (255, 255, 255)
LINE = (224, 225, 230)
WHITE = (19, 20, 23)
ON_BLUE = (255, 255, 255)
SUB = (104, 106, 114)
ACCENT = (0, 100, 255)
WARN = (254, 61, 22)
ASSETS = Path(__file__).resolve().parents[2] / "app/src/main/assets/entrances"

# 앱이 실제로 말하는 문장 (SpeechTexts.kt 와 같게)
TALL = " 주의하세요. 높이 제한이 2.3미터라 이 차는 들어가기 어려워요."
SPEECH = {
    "preview": "개발용 가상 상가 주차장 입구를 알려 드릴게요. 오른쪽, 편의점 바로 다음 경사로가 주차장 입구예요.",
    "preview_tall": "개발용 가상 상가 주차장 입구를 알려 드릴게요. 오른쪽, 편의점 바로 다음 경사로가 주차장 입구예요." + TALL,
    "preview_north_tall": "개발용 가상 상가 주차장 입구를 알려 드릴게요. 왼쪽, 약국을 지나서 바로 나오는 경사로가 주차장 입구예요." + TALL,
    "confuser": "첫 번째 경사로는 옆 건물 주차장이에요. 오른쪽 두 번째 경사로로 들어가세요. 세 번째 경사로는 다음 건물 □□타워 주차장이에요.",
    "passed": "입구를 지나쳤어요. 바로 다음 경사로는 다른 건물 주차장이니 들어가지 마세요. 내비가 다시 안내할 거예요.",
}

# (녹화 파일, 시작 초, 끝 초, 자막[(시작, 끝, 문장)], 음성[(시각, 키)])  시각은 잘라 낸 구간 기준
# 2026-11-10 녹화 기준. 음성 시각은 녹화할 때 Logcat(Guidance 태그)에 찍힌 시각에서 가져왔다.
CLIPS = [
    ("A.mp4", 12.0, 62.0,
     [(0.0, 8.3, "D단 주행 중 · 앱은 내비 옆 칸에서 기다립니다"),
      (8.4, 39.9, "입구 300m 전: 방향 카드 · 경사로 약도 · 높이 제한 · 입구 그림을 미리 보여 주고 음성 안내"),
      (40.0, 50.0, "입구 바로 앞: 정면 그림 + '첫 번째 경사로는 옆 건물' 경고")],
     [(8.4, "preview"), (40.0, "confuser")]),
    ("A.mp4", 70.0, 82.4,
     [(0.0, 5.5, "입구에 도착"),
      (5.6, 12.4, "P단 → 주차 완료: 주차장 정보 · 피드백 · 다음 단계(빌트인 캠) 안내")],
     []),
    ("B.mp4", 0.0, 38.0,
     [(0.0, 4.2, "다른 경우: 입구를 지나칠 때"),
      (4.3, 13.3, "입구 미리 보기"),
      (13.4, 27.6, "입구 앞 경고"),
      (27.7, 38.0, "입구에서 멀어지면 '지나쳤어요' 알림 → 길 찾기는 내비가 다시 합니다")],
     [(4.3, "preview"), (13.4, "confuser"), (27.7, "passed")]),
    ("C.mp4", 0.0, 48.0,
     [(0.0, 11.0, "추가 기능 ① 내 차 높이를 넣어 둡니다 (정차 중에만 보이는 설정)"),
      (11.0, 23.2, "② 북쪽에서 오면 그쪽 입구로 안내 · 높이 제한에 걸리면 경고"),
      (23.3, 34.3, "③ 남쪽에서 오면 원래 입구 · 경사로 약도와 높이 경고"),
      (34.4, 48.0, "④ 밤 모드: 화면과 입구 그림이 밤 버전으로 바뀝니다")],
     [(14.3, "preview_north_tall"), (28.4, "preview_tall"), (40.9, "confuser")]),
]


def font(size, bold=True):
    return ImageFont.truetype(FONT_B if bold else FONT_R, size)


def text_wrap(d, text, f, max_w):
    lines, cur = [], ""
    for word in text.split(" "):
        test = (cur + " " + word).strip()
        if d.textlength(test, font=f) <= max_w:
            cur = test
        else:
            lines.append(cur)
            cur = word
    lines.append(cur)
    return lines


def slide(name, title, draw_body):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, 8], fill=ACCENT)
    if title:
        d.text((120, 90), title, font=font(64), fill=WHITE)
    draw_body(img, d)
    path = BUILD / f"{name}.png"
    img.save(path)
    return path


def s_title(img, d):
    d.text((120, 330), "마지막300M", font=font(110), fill=WHITE)
    d.text((120, 480), "주차장 입구 안내 앱", font=font(48, False), fill=SUB)
    d.text((120, 580), "도착 직전, 붙어 있는 경사로 중 진짜 입구를 알려 주는 Pleos Connect 차량용 앱", font=font(44, False), fill=SUB)
    d.text((120, 900), "현대자동차그룹 차량용 앱 개발 공모전 · Pleos Connect 에뮬레이터 시연", font=font(32, False), fill=SUB)


def s_problem(img, d):
    lines = [
        ("내비는 '목적지 부근'에서 안내를 끝냅니다", WHITE),
        ("상가가 붙어 있으면 경사로가 연달아 있어", SUB),
        ("어느 쪽이 진짜 입구인지 헷갈립니다", SUB),
        ("지나치면 한 바퀴를 다시 돌아야 합니다", WARN),
    ]
    y = 260
    for t, c in lines:
        d.text((120, y), t, font=font(46 if c == WHITE else 40, c == WHITE), fill=c)
        y += 110
    far = Image.open(ASSETS / "dev-001-in-1-far.png").resize((760, 428))
    img.paste(far, (1060, 300))
    d.text((1060, 740), "예시 그림", font=font(28, False), fill=SUB)


def s_layout(img, d):
    boxes = [
        (120, "차량 정보", "Pleos 고정 영역", False),
        (700, "마지막300M", "우리 앱", True),
        (1280, "순정 내비", "길 안내", False),
    ]
    for x, t, s, ours in boxes:
        d.rounded_rectangle([x, 240, x + 520, 720], radius=24, fill=ACCENT if ours else CARD, outline=None if ours else LINE, width=2)
        d.text((x + 40, 420), t, font=font(48), fill=ON_BLUE if ours else WHITE)
        d.text((x + 40, 500), s, font=font(36, False), fill=ON_BLUE if ours else SUB)
    d.text((120, 790), "Pleos 분할 화면: 우리 앱을 내비 옆 칸에 두면 도착 직전에 입구 안내로 바뀝니다", font=font(40), fill=WHITE)
    d.text((120, 860), "길 안내는 내비가, 입구 확인은 우리 앱이 맡습니다 (경로 안내는 내비 하나만 하는 Pleos 정책)", font=font(34, False), fill=SUB)


def s_flow(img, d):
    steps = [
        ("① 입구 300m 전", "입구 그림 + 경사로 약도", "높이 제한 · 음성 안내"),
        ("② 입구 바로 앞", "정면 그림 + 약도", "옆 건물 입구 경고"),
        ("③ 지나치면", "바로 알림", "길 찾기는 내비가 다시"),
        ("④ P단", "주차 완료 · 주차장 정보", "입구가 맞았는지 피드백"),
    ]
    x = 100
    for i, (h, a, b) in enumerate(steps):
        d.rounded_rectangle([x, 300, x + 400, 720], radius=24, fill=CARD, outline=LINE, width=2)
        d.text((x + 30, 340), h, font=font(42), fill=ACCENT)
        for k, line in enumerate(text_wrap(d, a, font(32, False), 340) + text_wrap(d, b, font(32, False), 340)):
            d.text((x + 30, 440 + k * 56), line, font=font(32, False), fill=WHITE)
        if i < 3:
            d.polygon([(x + 410, 500), (x + 440, 510), (x + 410, 520)], fill=SUB)
        x += 440
    d.text((100, 800), "주행 중에는 큰 글씨 · 고대비 · 움직임 없는 화면, 버튼과 설정은 숨깁니다", font=font(36, False), fill=SUB)


def s_sdk(img, d):
    rows = [
        ("FusedLocation SDK", "차 위치 수신 → 입구까지 거리 계산"),
        ("Vehicle SDK", "기어(P단) → 주차 완료 화면"),
        ("NaviHelper SDK", "내비 목적지 → 입구 데이터 연결"),
        ("TextToSpeech SDK", "한국어 음성 안내"),
        ("CarUxRestrictions (AAOS)", "주행 중 화면 제한 대응"),
    ]
    y = 250
    for a, b in rows:
        d.rounded_rectangle([120, y, 1800, y + 120], radius=18, fill=CARD, outline=LINE, width=2)
        d.text((160, y + 32), a, font=font(44), fill=ACCENT)
        d.text((900, y + 36), b, font=font(40, False), fill=WHITE)
        y += 145


def s_arch(img, d):
    chain = ["Pleos SDK", "Controller", "판단 로직\nGuidanceEngine", "화면 · 음성"]
    x = 100
    for i, c in enumerate(chain):
        d.rounded_rectangle([x, 280, x + 380, 500], radius=24, fill=CARD if i != 2 else ACCENT, outline=LINE if i != 2 else None, width=2)
        for k, line in enumerate(c.split("\n")):
            f = font(42) if k == 0 else font(32, False)
            d.text((x + 30, 330 + k * 64), line, font=f, fill=WHITE if i != 2 else ON_BLUE)
        if i < 3:
            d.text((x + 392, 350), "→", font=font(56), fill=SUB)
        x += 460
    d.text((120, 580), "판단 로직은 SDK와 분리한 순수 코틀린 · 단위 테스트 9개", font=font(40), fill=WHITE)
    d.text((120, 660), "GPS 오차를 전제로: 좌표 대신 '몇 번째 경사로'와 간판처럼 눈에 보이는 단서로 안내", font=font(36, False), fill=SUB)


def s_section(img, d):
    d.text((120, 420), "실행 시연", font=font(96), fill=WHITE)
    d.text((120, 560), "Pleos Connect 에뮬레이터 v2.1.0 · 주행 위치는 스크립트로 입력 · 입구 그림은 예시", font=font(38, False), fill=SUB)


def s_outro(img, d):
    items = [
        "실제 장소 입구 사진 · 좌표 수집 중 (예시 그림 교체)",
        "빌트인 캠으로 입구 사진 자동 수집 (영상 API가 열리면)",
        "사용자 피드백으로 입구 데이터 고치기 · 출구 안내",
        "실차 검증과 입구 데이터 확대",
    ]
    y = 300
    for t in items:
        d.text((120, y), "· " + t, font=font(46, False), fill=WHITE)
        y += 110


SLIDES = [
    ("s1", None, s_title, 4.5),
    ("s2", "해결하려는 불편", s_problem, 7.0),
    ("s3", "화면 배치", s_layout, 7.0),
    ("s4", "안내 흐름", s_flow, 8.0),
    ("s5", "사용하는 Pleos SDK", s_sdk, 8.0),
    ("s6", "앱 내부 구조", s_arch, 7.0),
    ("s7", None, s_section, 3.0),
]
OUTRO = ("s9", "다음 단계", s_outro, 5.0)


def run(cmd, cwd=None):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print(r.stderr[-3000:])
        raise SystemExit(f"실패: {' '.join(map(str, cmd[:6]))} ...")


def make_tts():
    texts = BUILD / "tts.json"
    texts.write_text(json.dumps(SPEECH, ensure_ascii=False), encoding="utf-8")
    ps = BUILD / "tts.ps1"
    ps.write_text(
        "Add-Type -AssemblyName System.Speech\n"
        f"$t = Get-Content -Raw -Encoding UTF8 '{texts}' | ConvertFrom-Json\n"
        "foreach ($p in $t.PSObject.Properties) {\n"
        "  $s = New-Object System.Speech.Synthesis.SpeechSynthesizer\n"
        "  $s.SelectVoice('Microsoft Heami Desktop')\n"
        "  $s.Rate = 1\n"
        f"  $s.SetOutputToWaveFile('{BUILD}' + '\\tts_' + $p.Name + '.wav')\n"
        "  $s.Speak($p.Value)\n"
        "  $s.Dispose()\n"
        "}\n",
        encoding="utf-8-sig",
    )
    run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ps)])


def ass_time(t):
    h = int(t // 3600); m = int(t % 3600 // 60); s = t % 60
    return f"{h}:{m:02d}:{s:05.2f}"


def make_ass(name, captions):
    head = (
        "[Script Info]\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\n\n"
        "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, "
        "MarginL, MarginR, MarginV, Encoding\n"
        "Style: Cap,Malgun Gothic,46,&H00FFFFFF,&H00FFFFFF,&H00000000,&HB0000000,1,0,0,0,100,100,0,0,3,18,0,2,80,80,60,1\n"
        "Style: Tag,Malgun Gothic,28,&H00D0D0D0,&H00FFFFFF,&H00000000,&HB0000000,0,0,0,0,100,100,0,0,3,10,0,9,40,40,30,1\n\n"
        "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
    )
    total = max(e for _, e, _ in captions)
    lines = [f"Dialogue: 0,{ass_time(0)},{ass_time(total)},Tag,,0,0,0,,에뮬레이터 화면 · 입구 그림은 예시"]
    for s, e, t in captions:
        lines.append(f"Dialogue: 1,{ass_time(s)},{ass_time(e)},Cap,,0,0,0,,{t}")
    (BUILD / f"{name}.ass").write_text(head + "\n".join(lines) + "\n", encoding="utf-8")


def encode_slide(name, png, dur):
    run([FFMPEG, "-y", "-loop", "1", "-t", str(dur), "-i", str(png),
         "-f", "lavfi", "-t", str(dur), "-i", "anullsrc=r=48000:cl=stereo",
         "-vf", f"fps={FPS},format=yuv420p", "-c:v", "libx264", "-preset", "medium", "-crf", "18",
         "-c:a", "aac", "-b:a", "160k", "-shortest", str(BUILD / f"{name}.mp4")])


def encode_clip(idx, clip):
    src, start, end, captions, speech = clip
    name = f"c{idx}"
    dur = end - start
    make_ass(name, captions)
    inputs = ["-ss", str(start), "-t", str(dur), "-i", str(RAW / src),
              "-f", "lavfi", "-t", str(dur), "-i", "anullsrc=r=48000:cl=stereo"]
    filters = [f"[0:v]scale={W}:{H},fps={FPS},subtitles={name}.ass:fontsdir=.,format=yuv420p[v]"]
    mix = ["[1:a]"]
    for k, (t, key) in enumerate(speech):
        inputs += ["-i", f"tts_{key}.wav"]
        ms = int(t * 1000)
        filters.append(f"[{k + 2}:a]aresample=48000,aformat=channel_layouts=stereo,volume=2.0,adelay={ms}|{ms}[s{k}]")
        mix.append(f"[s{k}]")
    filters.append(f"{''.join(mix)}amix=inputs={len(mix)}:duration=first:normalize=0[a]")
    run([FFMPEG, "-y", *inputs, "-filter_complex", ";".join(filters), "-map", "[v]", "-map", "[a]",
         "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-c:a", "aac", "-b:a", "160k",
         "-t", str(dur), f"{name}.mp4"], cwd=BUILD)
    return BUILD / f"{name}.mp4"


def main():
    shutil.copy(FONT_B, BUILD / "malgunbd.ttf")
    shutil.copy(FONT_R, BUILD / "malgun.ttf")
    make_tts()
    parts = []
    for name, title, fn, dur in SLIDES:
        encode_slide(name, slide(name, title, fn), dur)
        parts.append(BUILD / f"{name}.mp4")
    for i, clip in enumerate(CLIPS):
        parts.append(encode_clip(i, clip))
    name, title, fn, dur = OUTRO
    encode_slide(name, slide(name, title, fn), dur)
    parts.append(BUILD / f"{name}.mp4")

    lst = BUILD / "list.txt"
    lst.write_text("".join(f"file '{p.name}'\n" for p in parts), encoding="utf-8")
    final = OUT / "demo_parking_entrance.mp4"
    run([FFMPEG, "-y", "-f", "concat", "-safe", "0", "-i", "list.txt", "-c", "copy", "-movflags", "+faststart", str(final)], cwd=BUILD)
    print("완성:", final)


if __name__ == "__main__":
    main()
