"""시험용 입구 '예시 그림'을 운전자 시점으로 그린다 (실제 사진 아님).

운전자는 경사로를 정면으로 보지 못하고, 차선에서 오른쪽 앞을 비스듬히 본다 (docs/data-collection.md 4절).
- far : 입구 앞 차선에서 진행 방향 오른쪽 앞. 앞에서부터 ① 옆 건물 경사로 → 편의점 → ② 목적지 경사로 순서
- near: 꺾기 직전 입구를 비스듬히. 간판·높이 제한 바·2.3m 표지판 + 우회전 화살표
- *-night: 같은 장면을 어둡게 하고 간판·가게 불빛만 남긴다

방법: 건물 벽면을 평면 그림(입면)으로 그린 뒤, 카메라 원근에 맞춰 비틀어 붙인다(원근 변환).
사용: python tools/draw_example_entrance.py app/src/main/assets/entrances   (Pillow 필요)
"""
import random
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

OUT = sys.argv[1]
SS = 2                      # 2배로 그린 뒤 줄여서 계단 현상을 줄인다
W, H = 1600 * SS, 900 * SS
PPM = 70                    # 벽면 그림의 1m 당 픽셀
FONT_B = "C:/Windows/Fonts/malgunbd.ttf"
FONT_R = "C:/Windows/Fonts/malgun.ttf"

# 세계 좌표(m): X 오른쪽, Y 위, Z 진행 방향. 운전자 눈높이 1.2m, 차는 X=0 차선
BUILDING_LINE = 8.0          # 오른쪽 건물 벽
CURB = 3.5                   # 오른쪽 연석 (연석~건물 벽 = 인도)
LEFT_LINE = -12.0            # 맞은편 건물 벽
RAMP_TOP = 2.6               # 경사로 입구 높이
RAMP1 = (9.8, 14.0)          # 옆 건물 경사로 (첫 번째)
RAMP2 = (19.0, 24.5)         # 목적지 경사로 (두 번째, 편의점 바로 다음)


def font(size, bold=True):
    return ImageFont.truetype(FONT_B if bold else FONT_R, int(size))


# ---------- 원근 ----------

class Cam:
    def __init__(self, z, f, cx, cy, eye=1.2):
        self.z, self.f, self.cx, self.cy, self.eye = z, f * SS, cx * SS, cy * SS, eye

    def p(self, X, Y, Z):
        dz = Z - self.z
        return (self.cx + self.f * X / dz, self.cy - self.f * (Y - self.eye) / dz)

    @property
    def near(self):
        return self.z + 0.6


def solve(A, b):
    n = len(b)
    M = [row[:] + [b[i]] for i, row in enumerate(A)]
    for c in range(n):
        piv = max(range(c, n), key=lambda r: abs(M[r][c]))
        M[c], M[piv] = M[piv], M[c]
        pv = M[c][c]
        M[c] = [v / pv for v in M[c]]
        for r in range(n):
            if r != c and M[r][c]:
                k = M[r][c]
                M[r] = [a - k * b_ for a, b_ in zip(M[r], M[c])]
    return [M[i][n] for i in range(n)]


def perspective_coeffs(dst, src):
    """화면 점(dst) → 벽면 그림 점(src) 으로 가는 원근 변환 계수 (PIL PERSPECTIVE 형식)"""
    A, b = [], []
    for (x, y), (u, v) in zip(dst, src):
        A.append([x, y, 1, 0, 0, 0, -u * x, -u * y]); b.append(u)
        A.append([0, 0, 0, x, y, 1, -v * x, -v * y]); b.append(v)
    return solve(A, b)


# ---------- 벽면(입면) 그림 ----------

class Facade:
    """X=line 평면 위 z0~z1, 높이 h 인 건물 벽. side R(오른쪽 건물) / L(맞은편 건물).
    벽을 마주 봤을 때의 왼쪽이 그림의 왼쪽이 되게 그린다 (글자가 뒤집히지 않게)."""

    def __init__(self, line, z0, z1, h, color, side="R"):
        self.line, self.z0, self.z1, self.h, self.side = line, z0, z1, h, side
        self.tex = Image.new("RGBA", (int((z1 - z0) * PPM), int(h * PPM)), color + (255,))
        self.lit = Image.new("L", self.tex.size, 0)
        self.d, self.ld = ImageDraw.Draw(self.tex), ImageDraw.Draw(self.lit)

    def x(self, z):
        return (self.z1 - z) * PPM if self.side == "R" else (z - self.z0) * PPM

    def y(self, Y):
        return (self.h - Y) * PPM

    def box(self, za, zb, ya, yb):
        xa, xb = sorted((self.x(za), self.x(zb)))
        return [xa, self.y(yb), xb, self.y(ya)]

    def rect(self, za, zb, ya, yb, fill, lit=0, outline=None, width=0, radius=0):
        b = self.box(za, zb, ya, yb)
        if radius:
            self.d.rounded_rectangle(b, radius=radius, fill=fill, outline=outline, width=width)
        else:
            self.d.rectangle(b, fill=fill, outline=outline, width=width)
        if lit:
            self.ld.rectangle(b, fill=lit)

    def hole(self, za, zb, ya, yb):
        self.d.rectangle(self.box(za, zb, ya, yb), fill=(0, 0, 0, 0))

    def text(self, zc, yc, txt, size_m, fill, lit=0, bold=True):
        f = font(size_m * PPM, bold)
        l, t, r, b = self.d.textbbox((0, 0), txt, font=f)
        x, y = self.x(zc) - (r - l) / 2 - l, self.y(yc) - (b - t) / 2 - t
        self.d.text((x, y), txt, font=f, fill=fill)
        if lit:
            self.ld.rectangle([x + l - 4, y + t - 4, x + r + 4, y + b + 4], fill=lit)

    def windows(self, za, zb, ya, yb, cols, rows, frame, glass, lit_ratio=0.0, seed=0):
        rnd = random.Random(seed)
        cw, rh = (zb - za) / cols, (yb - ya) / rows
        for r in range(rows):
            for c in range(cols):
                a, b = za + c * cw + cw * 0.15, ya + r * rh + rh * 0.2
                on = rnd.random() < lit_ratio
                self.rect(a, a + cw * 0.7, b, b + rh * 0.6, (236, 214, 150) if on else glass,
                          lit=200 if on else 0, outline=frame, width=3)

    def hazard_bar(self, za, zb, ya, yb):
        self.rect(za, zb, ya, yb, (250, 200, 0))
        bx = self.box(za, zb, ya, yb)
        step = (bx[3] - bx[1]) * 1.6
        x = bx[0]
        while x < bx[2]:
            self.d.polygon([(x, bx[1]), (x + step / 2, bx[1]), (x + step / 2 - step / 3, bx[3]), (x - step / 3, bx[3])], fill=(25, 25, 25))
            x += step

    def place(self, canvas, litmask, cam):
        """카메라 앞에 보이는 부분만 잘라 원근 변환해서 붙인다"""
        z0 = max(self.z0, cam.near)
        if z0 >= self.z1:
            return
        tex, lit = self.tex, self.lit
        if z0 > self.z0:  # 카메라 뒤쪽 부분 잘라 내기
            cut = int((z0 - self.z0) * PPM)
            if self.side == "R":
                tex, lit = tex.crop((0, 0, tex.width - cut, tex.height)), lit.crop((0, 0, lit.width - cut, lit.height))
            else:
                tex, lit = tex.crop((cut, 0, tex.width, tex.height)), lit.crop((cut, 0, lit.width, lit.height))
        X, h, z1 = self.line, self.h, self.z1
        if self.side == "R":
            dst = [cam.p(X, h, z1), cam.p(X, h, z0), cam.p(X, 0, z0), cam.p(X, 0, z1)]
        else:
            dst = [cam.p(X, h, z0), cam.p(X, h, z1), cam.p(X, 0, z1), cam.p(X, 0, z0)]
        w, hh = tex.size
        c = perspective_coeffs(dst, [(0, 0), (w, 0), (w, hh), (0, hh)])
        canvas.alpha_composite(tex.transform(canvas.size, Image.Transform.PERSPECTIVE, c, Image.Resampling.BICUBIC))
        warped = lit.transform(canvas.size, Image.Transform.PERSPECTIVE, c, Image.Resampling.BILINEAR)
        litmask.paste(ImageChops.lighter(litmask, warped))


# ---------- 장면 ----------

def ramp_facade(fac, ramp, sign, sign_bg, sign_lit):
    a, b = ramp
    fac.hole(a, b, 0, RAMP_TOP)
    fac.hazard_bar(a - 0.2, b + 0.2, RAMP_TOP + 0.05, RAMP_TOP + 0.35)
    fac.rect(a + 0.2, b - 0.2, RAMP_TOP + 0.5, RAMP_TOP + 1.35, sign_bg, lit=sign_lit, radius=10)
    fac.text((a + b) / 2, RAMP_TOP + 0.92, sign, 0.55, (255, 255, 255))


def build_world():
    rf = []
    # ① 옆 건물 △△빌딩 (첫 번째 경사로)
    f = Facade(BUILDING_LINE, 2.0, 14.5, 14, (168, 164, 158))
    f.windows(2.4, 14.2, 4.4, 13.2, 6, 3, (120, 118, 114), (150, 172, 192), 0.3, 1)
    f.rect(2.0, 14.5, 13.3, 14.0, (140, 136, 130))
    f.rect(3.2, 8.4, 0, 3.1, (110, 140, 165), lit=120)          # 로비 유리
    ramp_facade(f, RAMP1, "△△빌딩 주차장", (60, 60, 60), 0)
    rf.append(f)
    # 편의점 (경사로 사이, 낮은 건물)
    f = Facade(BUILDING_LINE, 14.5, 18.6, 4.8, (236, 236, 230))
    f.rect(14.5, 18.6, 3.2, 4.3, (0, 135, 90), lit=255)
    f.text(16.55, 3.75, "편의점 24", 0.72, (255, 255, 255))
    f.rect(14.9, 18.2, 0, 2.9, (170, 205, 220), lit=255, outline=(90, 90, 90), width=4)
    rf.append(f)
    # ② 목적지 ○○프라자 (두 번째 경사로)
    f = Facade(BUILDING_LINE, 18.6, 46, 20, (214, 196, 168))
    f.windows(19, 45.5, 5.2, 18.6, 9, 4, (160, 140, 110), (150, 172, 192), 0.35, 2)
    f.rect(18.6, 46, 18.8, 20, (190, 170, 140))
    f.text(32, 19.4, "○○프라자", 1.0, (80, 60, 40))
    ramp_facade(f, RAMP2, "P 주차장 입구", (20, 90, 200), 255)
    f.d.ellipse(f.box(25.0, 26.5, 1.0, 2.5), fill=(255, 255, 255), outline=(220, 30, 30), width=int(0.16 * PPM))  # 높이 제한 표지
    f.ld.ellipse(f.box(25.0, 26.5, 1.0, 2.5), fill=160)
    f.text(25.75, 1.75, "2.3m", 0.42, (0, 0, 0))
    f.rect(25.0, 27.6, 0.15, 0.85, (30, 60, 120), lit=140, radius=6)
    f.text(26.3, 0.5, "10분 1,000원", 0.3, (255, 255, 255), bold=False)
    f.rect(29, 35, 0, 3.4, (110, 140, 165), lit=150)                 # 로비
    rf.append(f)
    f = Facade(BUILDING_LINE, 46, 80, 12, (150, 156, 166))
    f.windows(46.5, 79.5, 3.5, 11.5, 8, 3, (110, 116, 126), (150, 172, 192), 0.3, 3)
    rf.append(f)
    f = Facade(BUILDING_LINE, 80, 260, 16, (176, 170, 160))
    f.windows(80.5, 259, 3.5, 15, 30, 4, (130, 126, 120), (150, 172, 192), 0.3, 4)
    rf.append(f)

    lf = []
    for i, (z0, z1, h, col) in enumerate([(5, 30, 12, (160, 160, 168)), (30, 58, 18, (190, 180, 168)),
                                          (58, 90, 10, (150, 160, 150)), (90, 260, 15, (170, 166, 176))]):
        f = Facade(LEFT_LINE, z0, z1, h, col, side="L")
        f.windows(z0 + 0.5, z1 - 0.5, 3.5, h - 1, max(3, int((z1 - z0) / 4)), max(2, int(h / 4)),
                  (120, 120, 126), (150, 172, 192), 0.3, 10 + i)
        f.rect(z0 + 1, z0 + 4, 0, 3, (110, 140, 165), lit=120)
        lf.append(f)
    return rf, lf


def poly(d, cam, pts, fill):
    d.polygon([cam.p(*pt) for pt in pts], fill=fill)


def draw_ground(img, cam):
    d = ImageDraw.Draw(img)
    for y in range(int(cam.cy) + 1):  # 하늘
        t = y / cam.cy
        d.line([(0, y), (W, y)], fill=(int(150 + 60 * t), int(190 + 40 * t), int(230 + 15 * t)))
    d.rectangle([0, cam.cy, W, H], fill=(72, 74, 80))       # 도로
    zn, zf = cam.near, 400
    poly(d, cam, [(CURB, 0, zn), (BUILDING_LINE, 0, zn), (BUILDING_LINE, 0, zf), (CURB, 0, zf)], (178, 174, 168))
    poly(d, cam, [(LEFT_LINE + 2.5, 0, zn), (LEFT_LINE, 0, zn), (LEFT_LINE, 0, zf), (LEFT_LINE + 2.5, 0, zf)], (170, 166, 160))
    for a, b in (RAMP1, RAMP2):  # 경사로 앞 인도 (차가 지나가는 턱 낮춘 부분)
        a = max(a, zn)
        if a < b:
            poly(d, cam, [(CURB, 0, a), (BUILDING_LINE, 0, a), (BUILDING_LINE, 0, b), (CURB, 0, b)], (140, 140, 140))
    # 연석 윗면
    poly(d, cam, [(CURB - 0.15, 0.02, zn), (CURB, 0.02, zn), (CURB, 0.02, zf), (CURB - 0.15, 0.02, zf)], (205, 202, 196))
    # 차선: 오른쪽 가장자리 실선, 차로 경계 점선, 중앙선(노랑)
    poly(d, cam, [(3.05, 0, zn), (3.2, 0, zn), (3.2, 0, zf), (3.05, 0, zf)], (230, 230, 230))
    z = 2.0
    while z < 200:
        a = max(z, zn)
        if a < z + 3:
            poly(d, cam, [(-1.85, 0, a), (-1.7, 0, a), (-1.7, 0, z + 3), (-1.85, 0, z + 3)], (235, 235, 235))
        z += 9
    for x in (-5.35, -5.1):
        poly(d, cam, [(x, 0, zn), (x + 0.15, 0, zn), (x + 0.15, 0, zf), (x, 0, zf)], (240, 196, 40))


def draw_ramp_interior(img, litmask, cam, ramp):
    """경사로 안쪽(아래로 내려가는 바닥·옆벽·천장)을 그리고, 입구 구멍 모양으로 잘라 붙인다"""
    a, b = max(ramp[0], cam.near), ramp[1]
    if a >= b:
        return
    X, D, drop, T = BUILDING_LINE, 10.0, 3.0, RAMP_TOP
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ll = Image.new("L", img.size, 0)
    d, dl = ImageDraw.Draw(layer), ImageDraw.Draw(ll)
    opening = [cam.p(X, T, a), cam.p(X, T, b), cam.p(X, 0, b), cam.p(X, 0, a)]
    d.polygon(opening, fill=(20, 22, 26, 255))
    poly(d, cam, [(X, T, a), (X, T, b), (X + D, T - drop, b), (X + D, T - drop, a)], (40, 42, 48, 255))   # 천장
    poly(d, cam, [(X, T, b), (X + D, T - drop, b), (X + D, -drop, b), (X, 0, b)], (56, 58, 64, 255))        # 안쪽 옆벽
    poly(d, cam, [(X, 0, a), (X, 0, b), (X + D, -drop, b), (X + D, -drop, a)], (96, 98, 104, 255))          # 내려가는 바닥
    for i in range(1, 8):  # 바닥 줄무늬
        t = i / 8
        d.line([cam.p(X + D * t, -drop * t, a), cam.p(X + D * t, -drop * t, b)], fill=(126, 128, 134, 255), width=3 * SS)
    lamp = [cam.p(X + 2.5, T - 0.77, a + 0.5), cam.p(X + 2.5, T - 0.77, b - 0.5),
            cam.p(X + 3.0, T - 0.9, b - 0.5), cam.p(X + 3.0, T - 0.9, a + 0.5)]
    d.polygon(lamp, fill=(235, 235, 220, 255))   # 천장 조명
    dl.polygon(lamp, fill=255)
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).polygon(opening, fill=255)
    img.paste(layer, (0, 0), mask)
    litmask.paste(ImageChops.lighter(litmask, ImageChops.multiply(ll, mask)))


def street_lamp(img, litmask, cam, z):
    if z <= cam.near:
        return
    d, dl = ImageDraw.Draw(img), ImageDraw.Draw(litmask)
    base, top = cam.p(CURB + 0.4, 0, z), cam.p(CURB + 0.4, 6.5, z)
    d.line([base, top], fill=(90, 94, 100), width=max(2, int(0.18 * cam.f / (z - cam.z))))
    head = cam.p(CURB - 0.6, 6.4, z)
    d.line([top, head], fill=(90, 94, 100), width=max(2, int(0.12 * cam.f / (z - cam.z))))
    r = 0.35 * cam.f / (z - cam.z)
    d.ellipse([head[0] - r, head[1] - r / 2, head[0] + r, head[1] + r / 2], fill=(240, 236, 210))
    dl.ellipse([head[0] - r, head[1] - r / 2, head[0] + r, head[1] + r / 2], fill=255)


def render_scene(cam):
    img = Image.new("RGBA", (W, H))
    lit = Image.new("L", (W, H), 0)
    draw_ground(img, cam)
    right, left = build_world()
    for f in left:
        f.place(img, lit, cam)
    for r in (RAMP1, RAMP2):
        draw_ramp_interior(img, lit, cam, r)
    for f in reversed(right):  # 먼 건물부터
        f.place(img, lit, cam)
    for z in (28.0, 52.0, 80.0):
        street_lamp(img, lit, cam, z)
    return img.convert("RGB"), lit


def nightify(day, lit):
    r, g, b = day.split()
    dark = Image.merge("RGB", [r.point(lambda v: int(v * 0.28 + 6)), g.point(lambda v: int(v * 0.30 + 8)),
                               b.point(lambda v: int(v * 0.42 + 24))])
    out = Image.composite(day, dark, lit)
    soft = lit.filter(ImageFilter.GaussianBlur(14 * SS))
    glow = Image.composite(day.filter(ImageFilter.GaussianBlur(10 * SS)), Image.new("RGB", day.size), soft)
    return ImageChops.add(out, glow.point(lambda v: int(v * 0.55)))


# ---------- 안내 표시 ----------

def opening_quad(cam, ramp):
    a, b = max(ramp[0], cam.near), ramp[1]
    X = BUILDING_LINE
    return [cam.p(X, RAMP_TOP, b), cam.p(X, RAMP_TOP, a), cam.p(X, 0, a), cam.p(X, 0, b)]


def grow(q, k):
    cx = sum(p[0] for p in q) / 4
    cy = sum(p[1] for p in q) / 4
    return [(cx + (x - cx) * k, cy + (y - cy) * k) for x, y in q]


def pill(d, cx, cy, text, bg, fg, size):
    f = font(size * SS)
    l, t, r, b = d.textbbox((0, 0), text, font=f)
    w, h = r - l, b - t
    px, py = 22 * SS, 12 * SS
    d.rounded_rectangle([cx - w / 2 - px, cy - h / 2 - py, cx + w / 2 + px, cy + h / 2 + py], radius=12 * SS, fill=bg)
    d.text((cx - w / 2 - l, cy - h / 2 - t), text, font=f, fill=fg)


def mark_target(d, q, label, pos=None):
    g = grow(q, 1.12)
    d.line(g + [g[0]], fill=(255, 196, 0), width=9 * SS, joint="curve")
    if pos is None:
        pos = (sum(p[0] for p in q) / 4, max(p[1] for p in q) + 62 * SS)
    pill(d, pos[0], pos[1], label, (255, 196, 0), (0, 0, 0), 34)


def mark_wrong(d, q, label):
    tl, tr, br, bl = grow(q, 0.82)
    d.line([tl, br], fill=(255, 80, 60), width=11 * SS)
    d.line([tr, bl], fill=(255, 80, 60), width=11 * SS)
    bx = sum(p[0] for p in q) / 4
    by = max(p[1] for p in q)
    pill(d, min(bx, W - 170 * SS), by + 62 * SS, label, (230, 60, 45), (255, 255, 255), 30)


def turn_arrow(img, start, end, ctrl):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    pts = []
    for i in range(41):
        t = i / 40
        pts.append(((1 - t) ** 2 * start[0] + 2 * (1 - t) * t * ctrl[0] + t * t * end[0],
                    (1 - t) ** 2 * start[1] + 2 * (1 - t) * t * ctrl[1] + t * t * end[1]))
    s = 60 * SS  # 화살촉 길이: 곡선 끝에서 이만큼 되돌아간 점을 화살촉 밑변으로 쓴다
    i, run = len(pts) - 1, 0.0
    while i > 0 and run < s:
        run += ((pts[i][0] - pts[i - 1][0]) ** 2 + (pts[i][1] - pts[i - 1][1]) ** 2) ** 0.5
        i -= 1
    (bx, by), (x1, y1) = pts[i], pts[-1]
    d.line(pts[:i + 1], fill=(255, 196, 0, 220), width=34 * SS, joint="curve")
    vx, vy = x1 - bx, y1 - by
    n = (vx * vx + vy * vy) ** 0.5
    vx, vy = vx / n, vy / n
    hw = 40 * SS
    d.polygon([(x1, y1), (bx - vy * hw, by + vx * hw), (bx + vy * hw, by - vx * hw)], fill=(255, 196, 0, 235))
    img.alpha_composite(layer)


def hood(d):
    d.polygon([(0, H), (0, 852 * SS), (420 * SS, 818 * SS), (1180 * SS, 818 * SS), (W, 852 * SS), (W, H)], fill=(28, 30, 34))
    d.line([(0, 852 * SS), (420 * SS, 818 * SS), (1180 * SS, 818 * SS), (W, 852 * SS)], fill=(70, 74, 80), width=3 * SS)


def badge(d, text, top_left=False):
    f = font(26 * SS, bold=top_left)
    l, t, r, b = d.textbbox((0, 0), text, font=f)
    w, h = r - l, b - t
    if top_left:
        box = [12 * SS, 12 * SS, 12 * SS + w + 28 * SS, 12 * SS + h + 20 * SS]
    else:
        box = [W - w - 40 * SS, H - h - 32 * SS, W - 12 * SS, H - 12 * SS]
    d.rounded_rectangle(box, radius=10 * SS, fill=(0, 0, 0))
    d.text((box[0] + 14 * SS - l, box[1] + 10 * SS - t), text, font=f, fill=(255, 255, 255))


def finish(base, overlay_fn, name, night):
    img = base.convert("RGBA")
    overlay_fn(img)
    d = ImageDraw.Draw(img)
    hood(d)
    badge(d, "예시 그림 · 실제 사진 아님")
    if night:
        badge(d, "밤 예시", top_left=True)
    img.convert("RGB").resize((W // SS, H // SS), Image.Resampling.LANCZOS).save(f"{OUT}/{name}.png", optimize=True)


def main():
    # far: 목적지 경사로 약 20m 앞 차선 (오른쪽 앞을 비스듬히, 경사로 두 개가 순서대로 보임)
    far_cam = Cam(z=0.0, f=1300, cx=420, cy=440)

    def far_marks(img):
        d = ImageDraw.Draw(img)
        mark_wrong(d, opening_quad(far_cam, RAMP1), "① 옆 건물")
        mark_target(d, opening_quad(far_cam, RAMP2), "② 여기로 진입")

    # near: 꺾기 직전, 목적지 경사로 약 7m 앞 (입구를 비스듬히)
    near_cam = Cam(z=11.5, f=1000, cx=380, cy=430)

    def near_marks(img):
        q = opening_quad(near_cam, RAMP2)  # [먼 쪽 위, 가까운 쪽 위, 가까운 쪽 아래, 먼 쪽 아래]
        d = ImageDraw.Draw(img)
        near_bottom = q[2]
        mark_target(d, q, "여기로 진입", pos=(near_bottom[0] - 70 * SS, near_bottom[1] + 70 * SS))
        # 바닥 가운데보다 조금 먼 쪽, 입구 안쪽을 가리킨다
        tip = (q[3][0] * 0.55 + q[2][0] * 0.45, (q[3][1] * 0.55 + q[2][1] * 0.45) - 40 * SS)
        turn_arrow(img, (720 * SS, 805 * SS), tip, (800 * SS, tip[1] + 120 * SS))

    for cam, marks, name in ((far_cam, far_marks, "dev-001-in-1-far"), (near_cam, near_marks, "dev-001-in-1-near")):
        day, lit = render_scene(cam)
        finish(day, marks, name, night=False)
        finish(nightify(day, lit), marks, name + "-night", night=True)
    print("ok")


main()
