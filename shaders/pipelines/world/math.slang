// Stateless math: luminance, GGX (D/G/VNDF + multi-scatter compensation), Fresnel, Henyey-Greenstein,
// the RR specular-albedo fit, the PCG stream, and direction/area-light sampling. No bindings, no globals.

import world_common;
import world_core;
import sky;

public float luminance(float3 c) { return dot(c, ACESCG_LUMA); }

// GGX normal distribution.
// NOTE: the 1e-7 denominator guard is not merely a divide-by-zero epsilon, and it is load-bearing. At
// the lobe peak d == a2, so the guard dominates PI*d*d until a2 >> 1.8e-4, i.e. rough >> 0.116; every
// smoother material has its NEE highlight peak truncated. That truncation is what keeps the sun from
// being counted twice: a specular continuation sets showCelestial, so the BSDF-sampled ray may hit the
// sun disc in world.rmiss while NEE is also adding a sun term for the same lobe, with no MIS weighting
// between them. Removing the guard restores the physical peak AND the double count, and makes the
// single-sample sun estimator explode wherever the lobe is tighter than the disc (~0.0047 rad). The
// real fix is MIS (or a disc-aware specular light sample), not an epsilon edit — left as-is.
public float ggxD(float ndh, float alpha) {
    float a2 = alpha * alpha;
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d + 1.0e-7);
}

// Smith GGX masking term for one direction (separable form; G2 = G1(v) * G1(l)).
public float ggxG1(float ndx, float alpha) {
    float a2 = alpha * alpha;
    return 2.0 * ndx / (ndx + sqrt(a2 + (1.0 - a2) * ndx * ndx) + 1.0e-7);
}

public float3 fresnelSchlick(float cosT, float3 f0) {
    float m = clamp(1.0 - cosT, 0.0, 1.0);
    float m2 = m * m;
    return f0 + (1.0 - f0) * (m2 * m2 * m);
}

// Degenerate-safe microfacet half-vector: opposing grazing vectors (l + v == 0) fall back to the surface
// normal instead of dividing zero by zero inside normalize().
public float3 safeHalfVector(float3 l, float3 v, float3 fallbackNormal) {
    float3 s = l + v;
    float len2 = dot(s, s);
    return len2 > 1.0e-12 ? s * rsqrt(len2) : fallbackNormal;
}

// Directional hemisphere albedo E_ss(mu, alpha) for single-scattering Smith GGX at F0 = 1:
//
//   E_ss = E_{h ~ VNDF(mu, alpha)}[ 1(n.l > 0) * G1(n.l, alpha) ]
//
// evaluated with the exact ggxG1()/sampleGGXVNDF() in this file (separable Smith, so the VNDF estimator
// weight reduces to G1(n.l)). The surface has a sharp ridge along mu ~ alpha (mu = ndv, alpha = linear
// roughness), which is why a plain bivariate polynomial fit needs 126 coefficients. Instead this uses a
// factored form that resolves the ridge with 24 scalars total:
//
//   E(mu, a) = 1 - (1 - G(a)) * S(x, a),    x = mu / (mu + a),
//
// where G(a) = E(mu -> 0, a) is a 2/2 rational (4 coefficients) and S = (1 - E) / (1 - G) is the
// normalized transition from 1 at mu << a to 0 at mu >> a. Writing
//
//   S = (1 - x) * (1 + x * P(x, a)),   P = sum_{i=0..4} sum_{j=0..3} c_ij x^i a^j,
//
// enforces S(0, a) = 1 and S(1, a) = 0 exactly, which keeps both physical limits (mu -> 0 and the
// alpha = 0 mirror limit) correct without spending coefficients on them. P contributes 20 scalars and G
// contributes 4; there are no arrays and no loops, and the whole evaluation is ~28 FMA plus one divide.
//
// Validation over a dense (mu, alpha) grid in [0.001, 1] x [0, 1] plus independent random samples gives
// max |E_actual / E_fit - 1| = 0.55% (rms 0.10%). Over the operational range the callers actually use
// (ndv >= 1e-4, alpha >= 1e-6) the worst case is 0.40%. alpha <= 1e-6 returns exactly 1.0, so smooth
// mirrors are untouched, and the final [0,1] clamp never engages inside the physical domain (the true
// minimum is ~0.3069 at alpha = mu = 1).
public float ggxDirectionalAlbedo(float ndv, float alpha) {
    float a = clamp(alpha, 0.0, 1.0);
    if (a <= 1.0e-6) {
        return 1.0; // exact smooth-mirror boundary
    }
    float mu = clamp(ndv, 0.0, 1.0);
    // Ratio coordinate: x = 0 at mu << alpha, x -> 1 at mu >> alpha. It compresses the ridge that the
    // un-factored fit could not follow, and is well conditioned for every alpha > 0.
    float x = mu / (mu + a);
    // P(x, a), Horner in a inside each row and Horner in x across rows.
    float p0 = 3.484377469f + a * (-5.216675688f + a * (6.492421524f - a * 2.022518857f));
    float p1 = -15.72219434f + a * (79.58942259f + a * (-99.62534707f + a * 36.40040609f));
    float p2 = 104.6498362f + a * (-458.8577045f + a * (608.1905474f - a * 240.3761784f));
    float p3 = -166.4541252f + a * (833.8672586f + a * (-1168.412449f + a * 475.6605309f));
    float p4 = 72.78500886f + a * (-468.1452876f + a * (755.893365f - a * 330.362324f));
    float p = p0 + x * (p1 + x * (p2 + x * (p3 + x * p4)));
    float s = (1.0 - x) * (1.0 + x * p);
    float g = (0.93617074f + 0.22100194f * a) / (1.0 + 0.25591182f * a + 0.62653022f * a * a);
    return clamp(1.0 - (1.0 - g) * s, 0.0, 1.0);
}

// Turquin multiple-scattering microfacet energy compensation factor: 1 + strength * F0 * (1 - E) / E.
// With E from the compact fit above, the white-furnace single-scatter deficit is reproduced to within
// 0.6%; the same scale is applied to both NEE and the BSDF continuation, so the two estimators stay
// consistent and grazing NEE cannot over-brighten.
public float3 ggxMultiScatterScale(float3 f0, float ndv, float alpha, float strength) {
    float k = clamp(strength, 0.0, 1.0);
    if (k <= 0.0 || alpha <= 1.0e-4) {
        return float3(1.0, 1.0, 1.0);
    }
    float e = max(ggxDirectionalAlbedo(ndv, alpha), 0.1);
    return float3(1.0, 1.0, 1.0) + clamp(f0, float3(0.0), float3(1.0)) * (k * (1.0 - e) / e);
}

// Henyey–Greenstein phase function (with 4π normalisation so it integrates to 1 over the sphere).
// g=0 → isotropic; g>0 → forward-scatter peak at cosT=1 (light and view aligned through a thin slab).
public static const float SSS_G        = 0.6; // forward-scatter anisotropy; leaves/grass are quite directional
public static const float SSS_STRENGTH = 1.0; // 1.0 ≈ 2.5× the Lambertian at peak (sss=1, cosT=1, backNdl=1)
// Keep SSS on the first two Pass B hits. Pass A's primary/interface prefix is outside this local depth.
public static const int   MAX_SSS_INDIRECT_DEPTH = 1;
public float hg(float cosT, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(max(0.0, 1.0 + g2 - 2.0 * g * cosT), 1.5));
}

// NVIDIA RR guide-buffer helper: integrated/view-dependent specular reflectivity, not raw F0.
// alpha is GGX alpha (== the linear roughness materials store), NoV is the first-hit view cosine.
public float3 rrSpecularAlbedo(float3 specularColor, float alpha, float NoV) {
    NoV = abs(NoV);
    float NoV2 = NoV * NoV;
    float alpha2 = alpha * alpha;
    float4 X = float4(1.0, NoV, NoV2, NoV * NoV2);
    float4 Y = float4(1.0, alpha, alpha2, alpha * alpha2);

    float2 m1 = float2(
            dot(float2(0.99044, -1.28514), X.xy),
            dot(float2(1.29678, -0.755907), X.xy));
    float3 Xxyw = float3(X.x, X.y, X.w);
    float3 m2 = float3(
            dot(float3(1.0, 2.92338, 59.4188), Xxyw),
            dot(float3(20.3225, -27.0302, 222.592), Xxyw),
            dot(float3(121.563, 626.13, 316.627), Xxyw));
    float bias = dot(m1, Y.xy) / max(dot(m2, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    float2 m3 = float2(
            dot(float2(0.0365463, 3.32707), X.xy),
            dot(float2(9.0632, -9.04756), X.xy));
    float3 Xxzw = float3(X.x, X.z, X.w);
    float3 m4 = float3(
            dot(float3(1.0, 3.59685, -1.36772), Xxzw),
            dot(float3(9.04401, -16.3174, 9.22949), Xxzw),
            dot(float3(5.56589, 19.7886, -20.2123), Xxzw));
    float scale = dot(m3, Y.xy) / max(dot(m4, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    bias *= clamp(specularColor.g * 50.0, 0.0, 1.0);
    return specularColor * max(0.0, scale) + float3(max(0.0, bias), max(0.0, bias), max(0.0, bias));
}

// Exact (unpolarized) Fresnel reflectance for a dielectric interface. cosI is the incidence cosine on
// the incoming side; etaI/etaT are the IORs the ray travels from / into. Returns 1.0 on total internal
// reflection (sin of the transmitted angle >= 1), which matches refract() returning the zero vector —
// so reflect/refract selection and TIR stay consistent.
public float fresnelDielectric(float cosI, float etaI, float etaT) {
    float sinT2 = (etaI * etaI) / (etaT * etaT) * max(0.0, 1.0 - cosI * cosI);
    if (sinT2 >= 1.0) {
        return 1.0;
    }
    float cosT = sqrt(1.0 - sinT2);
    float rs = (etaI * cosI - etaT * cosT) / (etaI * cosI + etaT * cosT);
    float rp = (etaI * cosT - etaT * cosI) / (etaI * cosT + etaT * cosI);
    return 0.5 * (rs * rs + rp * rp);
}

// PCG hash RNG.
public uint pcg(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}
public float rndf(inout uint s) {
    // Convert the high 24 bits, which are exactly representable as float. Converting all 32 bits first
    // lets the top 128 uint values round to 2^32, incorrectly returning 1.0 and breaking `< probability`
    // tests (most seriously the F == 1 total-internal-reflection branch).
    return float(pcg(s) >> 8u) * (1.0 / 16777216.0);
}

public float3 primaryRayDir(float2 ndc) {
    float4 nearH = mul(worldPush.invViewProj, float4(ndc.x, ndc.y, 1.0, 1.0));
    float4 farH = mul(worldPush.invViewProj, float4(ndc.x, ndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;
    return normalize(farP - nearP);
}

public float primaryRayConeSpread(float2 ndc, float2 size, float3 dir) {
    float2 onePixelNdc = 2.0 / size;
    float3 dx = primaryRayDir(ndc + float2(onePixelNdc.x, 0.0));
    float3 dy = primaryRayDir(ndc + float2(0.0, onePixelNdc.y));
    return max(max(length(cross(dir, dx)), length(cross(dir, dy))), RAY_CONE_MIN_SPREAD);
}

public void orthonormalBasis(float3 axis, out float3 tangent, out float3 bitangent) {
    tangent = normalize(abs(axis.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    bitangent = normalize(cross(axis, tangent));
    tangent = cross(bitangent, axis);
}

// Shirley-Chiu concentric mapping from [0,1)^2 onto the unit disk, preserving area uniformity for
// spherical emitter soft-shadow sampling.
public float2 sampleConcentricDisk(float u1, float u2) {
    float2 offset = float2(u1, u2) * 2.0 - float2(1.0, 1.0);
    if (offset.x == 0.0 && offset.y == 0.0) {
        return float2(0.0, 0.0);
    }
    float r;
    float theta;
    if (abs(offset.x) > abs(offset.y)) {
        r = offset.x;
        theta = (PI * 0.25) * (offset.y / offset.x);
    } else {
        r = offset.y;
        theta = (PI * 0.5) - (PI * 0.25) * (offset.x / offset.y);
    }
    return r * float2(cos(theta), sin(theta));
}

// Cosine-weighted hemisphere sample about n (Malley's method). For a Lambertian BRDF this is the
// importance-sampling match: BRDF*cos/pdf = (albedo/PI)*cos / (cos/PI) = albedo, so the continuation
// throughput is just *= albedo with no PI/cos bookkeeping left over.
public float3 cosineDir(float3 n, inout uint s) {
    float u1 = rndf(s);
    float u2 = rndf(s);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float3 t;
    float3 b;
    orthonormalBasis(n, t, b);
    float3 local = float3(r * cos(phi), r * sin(phi), sqrt(max(0.0, 1.0 - u1)));
    return normalize(local.x * t + local.y * b + local.z * n);
}

// Soft shadows: sample a direction within the light's SQUARE angular extent about `axis`. MC's sun/moon
// are square quads, so the NEE shadow ray samples a square (not a cone) — the same square, in the same
// horizon-levelled tangent frame (sky.celestialSquareFrame), that world.rmiss draws the visible body in.
// Averaged over frames by DLSS-RR this yields soft penumbrae that widen with occluder distance
// (contact-hardening) for free. halfAngle <= 0 ⇒ exact direction (hard).
public float3 sampleSquare(float3 axis, float halfAngle, inout uint s) {
    float3 right;
    float3 up;
    celestialSquareFrame(axis, right, up);
    float t = tan(halfAngle);
    float u = (rndf(s) * 2.0 - 1.0) * t;
    float v = (rndf(s) * 2.0 - 1.0) * t;
    return normalize(axis + u * right + v * up);
}

// Sample a GGX visible-normal (VNDF, Heitz 2018) about geometric normal n for view ve, returning the
// sampled microfacet normal (half-vector) in world space. Paired with the separable Smith term, the
// importance-sampling weight reduces to F * G1(NdotL) (applied by the caller).
public float3 sampleGGXVNDF(float3 n, float3 ve, float alpha, inout uint s) {
    float3 t;
    float3 b;
    orthonormalBasis(n, t, b);
    float3 Ve = float3(dot(ve, t), dot(ve, b), dot(ve, n)); // view in tangent space (z = n)
    float a = alpha;
    float3 Vh = normalize(float3(a * Ve.x, a * Ve.y, Ve.z));
    float lensq = Vh.x * Vh.x + Vh.y * Vh.y;
    float3 T1 = lensq > 0.0 ? float3(-Vh.y, Vh.x, 0.0) * rsqrt(lensq) : float3(1.0, 0.0, 0.0);
    float3 T2 = cross(Vh, T1);
    float u1 = rndf(s);
    float u2 = rndf(s);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float p1 = r * cos(phi);
    float p2 = r * sin(phi);
    float ss = 0.5 * (1.0 + Vh.z);
    p2 = (1.0 - ss) * sqrt(1.0 - p1 * p1) + ss * p2;
    float3 Nh = p1 * T1 + p2 * T2 + sqrt(max(0.0, 1.0 - p1 * p1 - p2 * p2)) * Vh;
    float3 Ne = normalize(float3(a * Nh.x, a * Nh.y, max(0.0, Nh.z))); // microfacet normal, tangent space
    return normalize(Ne.x * t + Ne.y * b + Ne.z * n);                 // -> world space
}
