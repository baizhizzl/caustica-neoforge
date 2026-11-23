// PathSegment — a resumable continuation — plus its packed 48-byte buffer form. This is the record
// the primary pass writes and the indirect pass reads. Depends on medium.


// Everything needed to resume tracing from a point in the scene. The path tracer takes one of these and
// may hand back another, which is how a dielectric's two Fresnel continuations are traced without
// instantiating the path tracer twice: the second branch is DATA, not a second inlined copy. `throughput`
// already carries the branch weight, so segments are simply summed.
//
// This is deliberately shaped like a record that could live in a buffer rather than a register: the
// planned split into a primary/guide pass and an indirect pass hands continuations between dispatches,
// and this is the payload it would write.

import world_common;
import world_core;
import medium;

public struct PathSegment {
    public float3 ro;
    public float3 rd;
    public float3 throughput;
    public MediumStack medium;
    public float rayConeWidth;
    public float rayConeSpread;
    public uint seed;
    public int bounce;          // interfaces already consumed, so RR start and the bounce cap stay global
    public bool showCelestial;
    // True while this segment belongs to the visually-primary chain: the camera prefix and everything
    // before the first non-dielectric surface. The indirect pass evaluates volumetric scattering only on
    // such segments, and keeps the flag across water/glass so shafts remain continuous through windows.
    public bool primaryVisual;
    // Only the main queue record returned to queue[pixelIndex] by primary.rgen stores the camera→first-
    // dielectric distance here; the split record and all other continuations store 0. The indirect pass
    // reconstructs the camera ray and integrates that prefix exactly once, with pixel weight 1.0.
    public float preInterfaceDist;
};

public PathSegment makePathSegment(float3 ro, float3 rd, float3 throughput, MediumStack medium,
                            float rayConeWidth, float rayConeSpread, uint seed,
                            int bounce, bool showCelestial, bool primaryVisual,
                            float preInterfaceDist) {
    PathSegment s;
    s.ro = ro;
    s.rd = rd;
    s.throughput = throughput;
    s.medium = medium;
    s.rayConeWidth = rayConeWidth;
    s.rayConeSpread = rayConeSpread;
    s.seed = seed;
    s.bounce = bounce;
    s.showCelestial = showCelestial;
    s.primaryVisual = primaryVisual;
    s.preInterfaceDist = preInterfaceDist;
    return s;
}
// field is a uint, so Std430DataLayout gives this an exact 48-byte stride.
public struct PackedPathSegment {
    public float3 ro;
    public uint rd;
    public uint throughput;
    public uint currentExtinction;
    public uint outerExtinction;
    public uint mediumIors;
    public uint rayCone;
    public uint seed;
    public uint pathFlags;
    public uint nextRecord;
};

public static const uint PATH_NO_NEXT = 0xffffffffu;
// Set on queue[pixelIndex].pathFlags when Pass A terminated at bounce 0 (sky miss, opaque, cutout,
// entity, particle, or frosted dielectric) and wrote the exact primary hit/miss payload into the otherwise
// unused queue[baseRecordCount + pixelIndex] slot so Pass B skips re-tracing bounce 0 across all SPP samples.
public static const uint PATH_FLAG_CACHED_PRIMARY = 1u << 5u;

public float2 octEncode(float3 direction) {
    float3 n = direction / max(abs(direction.x) + abs(direction.y) + abs(direction.z), 1.0e-20);
    float2 signNotZero = float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    float2 oct = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * signNotZero;
    return oct * 0.5 + 0.5;
}

public float3 octDecode(float2 encoded) {
    float2 oct = encoded * 2.0 - 1.0;
    float3 n = float3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        float2 signNotZero = float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
        n.xy = (1.0 - abs(n.yx)) * signNotZero;
    }
    return normalize(n);
}

public uint packUnorm16x2(float2 v) {
    uint2 q = uint2(round(clamp(v, 0.0, 1.0) * 65535.0));
    return q.x | (q.y << 16u);
}

public float2 unpackUnorm16x2(uint p) {
    return float2(p & 0xffffu, p >> 16u) / 65535.0;
}

// DXGI_FORMAT_R9G9B9E5_SHAREDEXP, implemented locally because Slang exposes no packing intrinsic.
public uint packRgb9e5(float3 value) {
    float3 v = clamp(value, 0.0, 65408.0);
    float maxChannel = max(v.x, max(v.y, v.z));
    int expSigned = maxChannel < exp2(-16.0)
            ? 0 : max(int(floor(log2(maxChannel))) + 16, 0);
    uint exponent = min(uint(expSigned), 31u);
    float scale = exp2(float(int(exponent) - 24));
    uint maxMantissa = uint(floor(maxChannel / scale + 0.5));
    if (maxMantissa == 512u && exponent < 31u) {
        exponent++;
        scale *= 2.0;
    }
    uint3 mantissa = min(uint3(floor(v / scale + 0.5)), uint3(511u, 511u, 511u));
    return mantissa.x | (mantissa.y << 9u) | (mantissa.z << 18u) | (exponent << 27u);
}

public float3 unpackRgb9e5(uint p) {
    float scale = exp2(float(int(p >> 27u) - 24));
    return float3(p & 0x1ffu, (p >> 9u) & 0x1ffu, (p >> 18u) & 0x1ffu) * scale;
}

public PackedPathSegment packPathSegment(PathSegment seg, uint nextRecord) {
    PackedPathSegment p;
    p.ro = seg.ro;
    p.rd = packUnorm16x2(octEncode(seg.rd));
    p.throughput = packRgb9e5(seg.throughput);
    p.currentExtinction = packRgb9e5(seg.medium.current.extinction);
    p.outerExtinction = packRgb9e5(seg.medium.outer.extinction);
    p.mediumIors = packHalf2(float2(seg.medium.current.ior, seg.medium.outer.ior));
    p.rayCone = packHalf2(float2(seg.rayConeWidth, seg.rayConeSpread));
    p.seed = seg.seed;
    // pathFlags stays a single uint, so PackedPathSegment remains exactly 48 bytes:
    //   bits 0..3  bounce
    //   bit 4      primaryVisual
    //   bit 8      showCelestial
    //   bit 9/10   current/outer medium is water
    //   bits 16..31 fp16 preInterfaceDist (clamped to the largest finite half)
    p.pathFlags = (uint(seg.bounce) & 15u)
            | (seg.primaryVisual ? 1u << 4u : 0u)
            | (seg.showCelestial ? 1u << 8u : 0u)
            | (seg.medium.current.water ? 1u << 9u : 0u)
            | (seg.medium.outer.water ? 1u << 10u : 0u)
            | (f32tof16(clamp(seg.preInterfaceDist, 0.0, 65504.0)) << 16u);
    p.nextRecord = nextRecord;
    return p;
}

public PathSegment unpackPathSegment(PackedPathSegment p) {
    float2 iors = unpackHalf2(p.mediumIors);
    Medium current;
    current.ior = iors.x;
    current.extinction = unpackRgb9e5(p.currentExtinction);
    current.water = (p.pathFlags & (1u << 9u)) != 0u;
    Medium outer;
    outer.ior = iors.y;
    outer.extinction = unpackRgb9e5(p.outerExtinction);
    outer.water = (p.pathFlags & (1u << 10u)) != 0u;
    MediumStack medium;
    medium.current = current;
    medium.outer = outer;
    float2 cone = unpackHalf2(p.rayCone);
    float preInterfaceDist = f16tof32((p.pathFlags >> 16u) & 0xFFFFu);
    return makePathSegment(p.ro, octDecode(unpackUnorm16x2(p.rd)),
            unpackRgb9e5(p.throughput), medium, cone.x, cone.y, p.seed,
            int(p.pathFlags & 15u), (p.pathFlags & (1u << 8u)) != 0u,
            (p.pathFlags & (1u << 4u)) != 0u, preInterfaceDist);
}

// Reuses the 48-byte split-record slot on non-splitting primary pixels (sky miss, opaque, cutout,
// entity, particle, frosted dielectric) to carry the exact unquantized primary ray direction and
// shading payload into Pass B. Payload flags occupy the low 16 bits of seed; f0.z uses its high half,
// leaving rayCone available for both half-float IOR/transmission lanes. Motion is already in the guides.
public PackedPathSegment packCachedPrimaryHit(float3 exactRd, Payload hit) {
    PackedPathSegment p;
    p.ro = exactRd;
    p.rd = hit.surface0;
    p.throughput = hit.surface1;
    p.currentExtinction = hit.surface2;
    p.outerExtinction = asuint(hit.hitT);
    p.mediumIors = packHalf2(float2(hit.f0.x, hit.f0.y));
    p.rayCone = hit.iorTransmission;
    p.seed = (hit.flags & 0xffffu) | ((f32tof16(float(hit.f0.z)) & 0xffffu) << 16u);
    p.pathFlags = hit.roughMetal;
    p.nextRecord = hit.emissionSss;
    return p;
}

public Payload unpackCachedPrimaryHit(PackedPathSegment cached, out float3 exactRd) {
    exactRd = cached.ro;
    Payload hit;
    hit.surface0 = cached.rd;
    hit.surface1 = cached.throughput;
    hit.surface2 = cached.currentExtinction;
    hit.hitT = asfloat(cached.outerExtinction);
    hit.motionPrev = half3(0.0h, 0.0h, 0.0h);
    float2 f0xy = unpackHalf2(cached.mediumIors);
    hit.f0 = half3(half(f0xy.x), half(f0xy.y), half(f16tof32(cached.seed >> 16u)));
    hit.flags = cached.seed & 0xffffu;
    hit.roughMetal = cached.pathFlags;
    hit.emissionSss = cached.nextRecord;
    hit.iorTransmission = cached.rayCone;
    hit.rayCone = 0u;
    return hit;
}
