# -*- coding: utf-8 -*-
"""原创可爱蓝鲸 App 图标 —— 高清绘制后缩放到各密度。"""
from PIL import Image, ImageDraw
import os

S = 1024  # 超采样尺寸
s = S / 1024.0

def px(v): return int(v * s)

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

# ---------- 画布 ----------
img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# ---------- 圆形渐变背景 ----------
top = (95, 178, 245)     # 浅蓝
bot = (34, 110, 190)     # 深蓝
cx, cy, r = 512, 512, 500
for y in range(0, S):
    t = y / float(S)
    col = lerp(top, bot, t)
    # 圆内才填充
    dy = y - cy
    if abs(dy) >= r:
        continue
    halfw = int((r*r - dy*dy) ** 0.5)
    d.line([(cx-halfw, y), (cx+halfw, y)], fill=col + (255,))

# （无额外高光，保持背景纯净）

# ---------- 喷水（先画，位于鲸鱼后方）----------
water = (235, 250, 255, 255)
# 水柱
d.polygon([(px(470), px(300)), (px(452), px(200)), (px(470), px(120)),
           (px(492), px(196)), (px(520), px(295))], fill=water)
# 水花水滴
for (wx, wy, wr) in [(470,96,20),(430,150,14),(516,150,16),(452,110,11),(500,95,10)]:
    d.ellipse([px(wx-wr), px(wy-wr), px(wx+wr), px(wy+wr)], fill=water)

# ---------- 鲸鱼尾巴（右后方）----------
tail = (26, 92, 168, 255)
tail_light = (40, 118, 200, 255)
# 上尾鳍
d.polygon([(px(720), px(470)), (px(880), px(330)), (px(930), px(360)),
           (px(860), px(470))], fill=tail)
# 下尾鳍
d.polygon([(px(720), px(500)), (px(890), px(600)), (px(930), px(570)),
           (px(850), px(490))], fill=tail_light)
# 尾柄
d.polygon([(px(690), px(455)), (px(790), px(440)), (px(800), px(520)), (px(690), px(510))], fill=tail)

# ---------- 鲸鱼身体 ----------
body = (30, 104, 182, 255)
# 主身体椭圆
d.ellipse([px(200), px(360), px(760), px(700)], fill=body)
# 身体左侧圆头
d.ellipse([px(180), px(410), px(430), px(680)], fill=body)
# 背部稍微隆起
d.ellipse([px(360), px(330), px(660), px(560)], fill=body)

# ---------- 肚子（浅色）----------
belly = (196, 232, 255, 255)
d.ellipse([px(230), px(540), px(700), px(720)], fill=belly)
d.ellipse([px(210), px(560), px(400), px(690)], fill=belly)

# ---------- 胸鳍 ----------
fin = (22, 88, 160, 255)
# 侧身的小翅，朝斜下方，形状自然收尖
d.polygon([(px(560), px(600)), (px(690), px(620)), (px(700), px(690)),
           (px(600), px(720))], fill=fin)
d.ellipse([px(560), px(590), px(680), px(680)], fill=fin)

# ---------- 眼睛 ----------
# 眼白
d.ellipse([px(310), px(470), px(410), px(570)], fill=(255,255,255,255))
# 瞳孔
d.ellipse([px(338), px(493), px(392), px(547)], fill=(20,42,70,255))
# 高光
d.ellipse([px(348), px(500), px(366), px(518)], fill=(255,255,255,255))
d.ellipse([px(372), px(522), px(382), px(532)], fill=(255,255,255,220))

# ---------- 腮红 ----------
d.ellipse([px(288), px(560), px(360), px(596)], fill=(255,140,160,120))

# ---------- 微笑嘴 ----------
d.arc([px(330), px(540), px(470), px(640)], start=15, end=120, fill=(18,70,130,255), width=px(10))

# ---------- 背上的小书本（学习元素）----------
# 书本放在鲸鱼背部中央
book1 = (255,255,255,245)
book2 = (230,244,255,245)
d.polygon([(px(470), px(365)), (px(560), px(340)), (px(560), px(420)), (px(470), px(445))], fill=book1)
d.polygon([(px(560), px(340)), (px(650), px(365)), (px(650), px(445)), (px(560), px(420))], fill=book2)
# 书脊
d.line([(px(560), px(340)), (px(560), px(420))], fill=(150,190,225,255), width=px(6))
# 书页线条
for i in range(2):
    yy = px(375 + i*22)
    d.line([(px(490), yy), (px(545), yy-px(8))], fill=(170,205,235,255), width=px(5))
    d.line([(px(575), yy-px(8)), (px(630), yy)], fill=(170,205,235,255), width=px(5))

# ---------- 缩小并保存 ----------
d.map = None
os.makedirs('app/src/main/res/mipmap-mdpi', exist_ok=True)
sizes = {
    'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192
}
# 主图先存一份供预览
img.resize((512,512), Image.LANCZOS).save('_icon_preview.png')

for name, sz in sizes.items():
    small = img.resize((sz, sz), Image.LANCZOS)
    path = 'app/src/main/res/mipmap-%s/ic_launcher.png' % name
    small.save(path)
    print(name, sz, path)
