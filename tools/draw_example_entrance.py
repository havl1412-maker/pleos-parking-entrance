"""시험용 입구 '예시 그림' 두 장을 그린다 (실제 사진 아님).
far: 맞은편 인도에서 본 거리. 왼쪽부터 옆 건물(첫 번째 경사로) - 편의점 - 목적지 건물(두 번째 경사로)
near: 목적지 건물 경사로 정면
"""
import sys
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1]
FONT = "C:/Windows/Fonts/malgunbd.ttf"
FONT_R = "C:/Windows/Fonts/malgun.ttf"


def font(size, bold=True):
    return ImageFont.truetype(FONT if bold else FONT_R, size)


def sky_and_road(d, W, H, horizon):
    for y in range(horizon):  # 하늘 그라데이션
        t = y / horizon
        c = (int(150 + 60 * t), int(190 + 40 * t), int(230 + 15 * t))
        d.line([(0, y), (W, y)], fill=c)
    d.rectangle([0, horizon, W, H], fill=(70, 72, 78))  # 도로
    d.rectangle([0, horizon, W, horizon + 26], fill=(175, 172, 165))  # 건너편 인도
    d.rectangle([0, horizon + 26, W, horizon + 32], fill=(120, 118, 112))  # 연석


def window_grid(d, x0, y0, x1, y1, cols, rows, frame, glass):
    cw = (x1 - x0) / cols
    rh = (y1 - y0) / rows
    for r in range(rows):
        for c in range(cols):
            a = x0 + c * cw + cw * 0.15
            b = y0 + r * rh + rh * 0.18
            d.rectangle([a, b, a + cw * 0.7, b + rh * 0.62], fill=glass, outline=frame, width=2)


def ramp(d, x0, x1, top, bottom, label, label_bg, label_fg, f):
    """경사로 입구: 어두운 개구부 + 아래로 내려가는 바닥 + 위 간판"""
    d.rectangle([x0, top, x1, bottom], fill=(28, 30, 34))  # 개구부
    # 내려가는 바닥 (사다리꼴)
    mid = (x0 + x1) / 2
    d.polygon([(x0, bottom), (x1, bottom), (mid + (x1 - x0) * 0.22, top + (bottom - top) * 0.45),
               (mid - (x1 - x0) * 0.22, top + (bottom - top) * 0.45)], fill=(92, 94, 98))
    for i in range(1, 6):  # 바닥 줄무늬
        y = bottom - (bottom - top) * 0.55 * i / 6
        k = (x1 - x0) * (0.5 - 0.28 * i / 6)
        d.line([(mid - k, y), (mid + k, y)], fill=(120, 122, 126), width=2)
    # 높이 제한 바 (노랑·검정 줄)
    bar_y = top - 22
    d.rectangle([x0 - 6, bar_y, x1 + 6, bar_y + 16], fill=(250, 200, 0))
    step = 24
    for x in range(int(x0 - 6), int(x1 + 6), step * 2):
        d.polygon([(x, bar_y), (x + step, bar_y), (x + step - 8, bar_y + 16), (x - 8, bar_y + 16)], fill=(20, 20, 20))
    # 간판
    tw = d.textlength(label, font=f)
    lx = mid - tw / 2 - 14
    d.rounded_rectangle([lx, bar_y - f.size - 26, lx + tw + 28, bar_y - 8], radius=8, fill=label_bg)
    d.text((lx + 14, bar_y - f.size - 20), label, font=f, fill=label_fg)


def badge(img, text):
    d = ImageDraw.Draw(img)
    f = font(26, bold=False)
    tw = d.textlength(text, font=f)
    W, H = img.size
    d.rounded_rectangle([W - tw - 40, H - 54, W - 12, H - 12], radius=10, fill=(0, 0, 0))
    d.text((W - tw - 26, H - 50), text, font=f, fill=(255, 255, 255))


def draw_far():
    W, H = 1600, 900
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    horizon = 640
    sky_and_road(d, W, H, horizon)

    # 옆 건물 (회색, 첫 번째 경사로)
    d.rectangle([40, 150, 600, horizon], fill=(168, 164, 158))
    window_grid(d, 60, 175, 580, 430, 5, 3, (120, 118, 114), (150, 175, 195))
    d.rectangle([40, 120, 600, 150], fill=(140, 136, 130))
    d.text((70, 122), "△△빌딩", font=font(26), fill=(255, 255, 255))
    ramp(d, 300, 560, 500, horizon, "△△빌딩 주차장", (60, 60, 60), (255, 255, 255), font(30))
    d.rectangle([70, 470, 270, horizon], fill=(110, 140, 165))  # 로비 유리문

    # 편의점 (가운데, 낮은 건물)
    d.rectangle([600, 330, 880, horizon], fill=(236, 236, 230))
    d.rectangle([600, 360, 880, 430], fill=(0, 135, 90))
    d.text((640, 368), "편의점 24", font=font(44), fill=(255, 255, 255))
    d.rectangle([620, 450, 860, horizon], fill=(150, 190, 210), outline=(90, 90, 90), width=3)
    d.line([(740, 450), (740, horizon)], fill=(90, 90, 90), width=3)

    # 목적지 건물 (베이지, 두 번째 경사로)
    d.rectangle([880, 90, 1560, horizon], fill=(214, 196, 168))
    window_grid(d, 900, 120, 1540, 420, 6, 3, (160, 140, 110), (150, 175, 195))
    d.rectangle([880, 60, 1560, 90], fill=(190, 170, 140))
    d.text((910, 60), "○○프라자", font=font(28), fill=(80, 60, 40))
    ramp(d, 920, 1210, 500, horizon, "P 주차장 입구", (20, 90, 200), (255, 255, 255), font(32))
    d.rectangle([1260, 470, 1520, horizon], fill=(110, 140, 165))  # 로비

    # 차선
    for x in range(0, W, 140):
        d.rectangle([x, 790, x + 80, 802], fill=(235, 235, 235))

    # 안내 표시: 목적지 입구를 노란 테두리로 강조
    d.rounded_rectangle([905, 420, 1225, horizon + 6], radius=14, outline=(255, 196, 0), width=8)
    lf = font(34)
    lw = d.textlength("여기로 진입", font=lf)
    d.rounded_rectangle([1065 - lw / 2 - 24, 690, 1065 + lw / 2 + 24, 752], radius=12, fill=(255, 196, 0))
    d.text((1065 - lw / 2, 696), "여기로 진입", font=lf, fill=(0, 0, 0))
    # 첫 번째 경사로 X 표시
    d.line([(330, 520), (530, horizon - 10)], fill=(255, 90, 70), width=10)
    d.line([(530, 520), (330, horizon - 10)], fill=(255, 90, 70), width=10)

    badge(img, "예시 그림 · 실제 사진 아님")
    img.save(f"{OUT}/dev-001-in-1-far.png", optimize=True)


def draw_near():
    W, H = 1600, 900
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    horizon = 780
    sky_and_road(d, W, H, horizon)

    # 왼쪽 편의점 일부
    d.rectangle([0, 300, 330, horizon], fill=(236, 236, 230))
    d.rectangle([0, 330, 330, 420], fill=(0, 135, 90))
    d.text((24, 342), "편의점 24", font=font(54), fill=(255, 255, 255))
    d.rectangle([20, 450, 310, horizon], fill=(150, 190, 210), outline=(90, 90, 90), width=3)

    # 목적지 건물 정면
    d.rectangle([330, 40, 1600, horizon], fill=(214, 196, 168))
    window_grid(d, 360, 60, 1580, 300, 6, 1, (160, 140, 110), (150, 175, 195))
    ramp(d, 420, 1160, 470, horizon, "P 주차장 입구  ENTRANCE", (20, 90, 200), (255, 255, 255), font(46))
    # 높이 제한 표지
    d.ellipse([1220, 420, 1400, 600], fill=(255, 255, 255), outline=(220, 30, 30), width=16)
    d.text((1252, 470), "2.3m", font=font(52), fill=(0, 0, 0))
    d.text((1230, 610), "높이 제한", font=font(36), fill=(60, 40, 20))
    # 요금 안내판
    d.rounded_rectangle([1220, 670, 1560, horizon - 10], radius=8, fill=(30, 60, 120))
    d.text((1240, 684), "10분 1,000원 · 24시간", font=font(26, bold=False), fill=(255, 255, 255))

    badge(img, "예시 그림 · 실제 사진 아님")
    img.save(f"{OUT}/dev-001-in-1-near.png", optimize=True)


draw_far()
draw_near()
print("ok")
