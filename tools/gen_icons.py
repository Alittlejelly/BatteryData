"""从 design/app-icon-source.png 生成 Android 各密度图标资源。

素材是满幅绿色方块 + 白色电池图形：
- 自适应图标：背景用纯色 #4FD384，前景/单色用抠出的白色图形（透明底）
- 旧版图标：整图缩放，round 版本额外做圆形裁切
"""
import pathlib

from PIL import Image, ImageDraw

SRC = "design/app-icon-source.png"
BG = (79, 211, 132)  # #4FD384

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

# 自适应图标前景里，图形宽度占 108dp 画布的比例（素材原图里约 59%）
FOREGROUND_GLYPH_FRACTION = 0.60
# 旧版图标像素尺寸（48dp）
LEGACY_SIZE_DP = 48


def extract_glyph(img):
    """把白色图形从纯绿底上抠出来，返回白色 + alpha 的图（裁到图形外框）。"""
    img = img.convert("RGB")
    w, h = img.size
    px = img.load()
    mask = Image.new("L", (w, h), 0)
    mpx = mask.load()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            # 各通道相对背景的“向白色推进”比例，取平均作为 alpha
            a = 0.0
            for c, bg_c in ((r, BG[0]), (g, BG[1]), (b, BG[2])):
                a += (c - bg_c) / (255 - bg_c)
            a = max(0.0, min(1.0, a / 3.0))
            mpx[x, y] = int(round(a * 255))
    bbox = mask.getbbox()
    mask = mask.crop(bbox)
    glyph = Image.new("RGBA", mask.size, (255, 255, 255, 0))
    glyph.putalpha(mask)
    return glyph


def make_foreground(glyph, canvas_px):
    canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    target_w = int(round(canvas_px * FOREGROUND_GLYPH_FRACTION))
    ratio = target_w / glyph.width
    target_h = int(round(glyph.height * ratio))
    scaled = glyph.resize((target_w, target_h), Image.LANCZOS)
    canvas.alpha_composite(
        scaled,
        ((canvas_px - target_w) // 2, (canvas_px - target_h) // 2),
    )
    return canvas


def make_legacy(img, size_px, round_icon):
    full = img.convert("RGB").resize((size_px, size_px), Image.LANCZOS)
    if not round_icon:
        return full
    # 圆形图标：4 倍超采样做圆角裁切，边缘更干净
    ss = 4
    big = full.resize((size_px * ss, size_px * ss), Image.LANCZOS).convert("RGBA")
    mask = Image.new("L", big.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, big.size[0] - 1, big.size[1] - 1), fill=255)
    big.putalpha(mask)
    return big.resize((size_px, size_px), Image.LANCZOS)


def make_flat(img, size_px):
    """整图原样缩放（无遮罩），供关于页等界面直接显示。"""
    return img.convert("RGB").resize((size_px, size_px), Image.LANCZOS)


def main():
    img = Image.open(SRC)
    glyph = extract_glyph(img)
    print("glyph size", glyph.size)

    # 关于页用的静态图标：自适应图标 XML 被普通 Image 绘制时会按系统规则再缩放一次，
    # 直接用一张原尺寸位图，显示效果最可控
    pathlib.Path("app/src/main/res/drawable-nodpi").mkdir(parents=True, exist_ok=True)
    make_flat(img, 384).save("app/src/main/res/drawable-nodpi/ic_launcher_flat.png")

    for density, scale in DENSITIES.items():
        base = f"app/src/main/res/mipmap-{density}"

        # 自适应图标前景 / 单色：108dp 画布
        fg_px = int(round(108 * scale))
        fg = make_foreground(glyph, fg_px)
        fg.save(f"{base}/ic_launcher_foreground.png")
        fg.save(f"{base}/ic_launcher_monochrome.png")

        # 旧版图标：48dp
        legacy_px = int(round(LEGACY_SIZE_DP * scale))
        make_legacy(img, legacy_px, False).save(f"{base}/ic_launcher.webp", lossless=True)
        make_legacy(img, legacy_px, True).save(f"{base}/ic_launcher_round.webp", lossless=True)
        print(density, "fg", fg_px, "legacy", legacy_px)


if __name__ == "__main__":
    main()
