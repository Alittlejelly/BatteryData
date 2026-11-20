package com.smalljelly.batterydata.ui.components

/**
 * 顶栏「渐进模糊」的 AGSL 着色器（API 33+）。
 *
 * 和普通模糊的区别只有一条：**半径随 Y 递减**。
 * 顶部满半径 → 底端收到 0，于是带子最下沿的像素和它下面那份锐利内容是同一个东西，
 * 接缝自然消失；而整条带子可以保持完全不透明（不会像"半透明糊层盖在锐利内容上"那样发灰发淡）。
 *
 * 采样一律镜像折回带子内部：顶栏贴着屏幕顶边，向上的采样落到内容区外，
 * clamp 到边缘会把最上一行拉平（看起来就是"顶端没糊"），镜像则相当于把内容反射回来。
 */

/** 一圈螺旋上的采样点数：少了会在强模糊时出现明显噪点 */
private const val BLUR_TAPS = 28

/** 去噪环的采样点数（黄金角递推，避开 8 的倍数以免转回原点） */
private const val DENOISE_TAPS = 10

/**
 * 主 pass：黄金角螺旋 + 按面积均匀（sqrt）分布，高斯权重，半径再抖 ±10% 打散规则纹路。
 */
internal const val PROGRESSIVE_BLUR_AGSL = """
uniform shader content;
uniform float2 areaSize;
uniform float maxRadius;
uniform float edgeFadeStart;
layout(color) uniform half4 tint;
uniform float tintIntensity;

const float MIRROR_GAIN = 2.0;

float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float2 foldBack(float2 p, float2 span) {
    float2 mirrored = abs(p);
    mirrored += max(-p, float2(0.0)) * (MIRROR_GAIN - 1.0);
    return clamp(mirrored, float2(0.0), span);
}

half4 blurDisk(float2 coord, float radius, float2 span) {
    if (radius < 0.75) {
        return content.eval(coord);
    }
    float seed = hash12(coord);
    float2 dir = float2(cos(seed * 6.2831853), sin(seed * 6.2831853));
    float2 turn = float2(-0.7373688, -0.6754903);
    half4 total = half4(0.0);
    float weightSum = 0.0;
    for (int i = 0; i < $BLUR_TAPS; i++) {
        float fi = float(i);
        float f = (fi + 0.5) / $BLUR_TAPS.0;
        float r = radius * sqrt(f) * (0.90 + 0.20 * fract(seed * 93.9898 + fi * 0.7548776662));
        float w = exp(-f / 0.85);
        total += content.eval(foldBack(coord + dir * r, span)) * w;
        weightSum += w;
        dir = float2(dir.x * turn.x - dir.y * turn.y, dir.x * turn.y + dir.y * turn.x);
    }
    if (weightSum < 0.0001) {
        return content.eval(coord);
    }
    return total / weightSum;
}

half4 main(float2 coord) {
    float2 span = max(areaSize - 1.0, float2(1.0));
    if (coord.x < 0.0 || coord.y < 0.0 || coord.x > span.x || coord.y > span.y) {
        return content.eval(coord);
    }
    float t = clamp(coord.y / span.y, 0.0, 1.0);
    // 1 → 0 的平滑衰减：顶部满半径，底端趋近 0（这也是"渐进"的全部含义）
    float strength = 1.0 - smoothstep(0.0, 1.0, t);
    half4 color = blurDisk(coord, maxRadius * strength, span);
    // 最后几个像素收干净，兜住 1px 级的取整误差
    float edge = smoothstep(edgeFadeStart, 1.0, t);
    color *= (1.0 - edge);
    if (tintIntensity > 0.0) {
        color = mix(color, tint, tintIntensity * strength);
    }
    return color;
}
"""

/**
 * 去噪 pass：在半径 1/3 处做一圈平均，压掉主 pass 抖动采样留下的白噪。
 * 环半径必须够到主 pass 的样本间距，否则只是把颗粒磨细、看着一样噪。
 */
internal const val PROGRESSIVE_DENOISE_AGSL = """
uniform shader content;
uniform float2 areaSize;
uniform float maxRadius;

float2 foldBack(float2 p, float2 span) {
    float2 mirrored = abs(p);
    mirrored += max(-p, float2(0.0));
    return clamp(mirrored, float2(0.0), span);
}

half4 main(float2 coord) {
    float2 span = max(areaSize - 1.0, float2(1.0));
    if (coord.x < 0.0 || coord.y < 0.0 || coord.x > span.x || coord.y > span.y) {
        return content.eval(coord);
    }
    float t = clamp(coord.y / span.y, 0.0, 1.0);
    float r = maxRadius * (1.0 - smoothstep(0.0, 1.0, t)) * 0.33;
    if (r < 0.75) {
        return content.eval(coord);
    }
    half4 total = content.eval(coord) * 2.0;
    float weightSum = 2.0;
    float2 dir = float2(1.0, 0.0);
    float2 turn = float2(-0.7373688, -0.6754903);
    for (int i = 0; i < $DENOISE_TAPS; i++) {
        total += content.eval(foldBack(coord + dir * r, span));
        weightSum += 1.0;
        dir = float2(dir.x * turn.x - dir.y * turn.y, dir.x * turn.y + dir.y * turn.x);
    }
    return total / weightSum;
}
"""
