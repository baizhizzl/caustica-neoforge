// CPU-callable probes of the production shader helpers. No GPU resources are reachable from these
// exports, so Slang's LLVM backend can execute them without a Vulkan device or a Minecraft client.
import world_common;
import world_core;
import medium;
import segment;
import math;
import lighting;

uint cachedRoundTrip(uint flags, float hitT, float ior, float transmission) {
    Payload hit = {};
    packAlbedo(hit, float3(0.125, 0.5, 0.875));
    packNormal(hit, float3(-0.25, 0.5, 0.75));
    if (hitT < 0.0) {
        packSky(hit, float3(360000.0, 120000.0, 65504.0));
    }
    hit.hitT = hitT;
    hit.f0 = half3(0.04h, 0.71h, 0.95h);
    hit.flags = flags;
    hit.roughMetal = packHalf2(float2(0.25, 0.875));
    hit.emissionSss = packHalf2(float2(12000.0, 0.125));
    hit.iorTransmission = packHalf2(float2(ior, transmission));
    float3 exactRd = float3(0.125, -0.25, 0.5);
    float3 restoredRd;
    Payload restored = unpackCachedPrimaryHit(packCachedPrimaryHit(exactRd, hit), restoredRd);
    uint failed = 0u;
    if (any(restoredRd != exactRd)) failed |= 1u;
    if (restored.surface0 != hit.surface0 || restored.surface1 != hit.surface1
            || restored.surface2 != hit.surface2) failed |= 2u;
    if (asuint(restored.hitT) != asuint(hit.hitT)) failed |= 4u;
    if (any(restored.f0 != hit.f0)) failed |= 8u;
    if (restored.flags != hit.flags) failed |= 16u;
    if (restored.roughMetal != hit.roughMetal) failed |= 32u;
    if (restored.emissionSss != hit.emissionSss) failed |= 64u;
    if (restored.iorTransmission != hit.iorTransmission) failed |= 128u;
    return failed;
}

export __extern_cpp uint testCachedPrimaryRoundTrip() {
    if (sizeof(PackedPathSegment) != 48) return 256u;
    uint failed = 0u;
    // Exercise each defined payload flag and every material model, including both sky and hit unions.
    uint allFlags = PAYLOAD_SHOW_CELESTIAL | PAYLOAD_DIELECTRIC_ENTERING
            | (PAYLOAD_EMISSION_SOURCE_MASK << PAYLOAD_EMISSION_SOURCE_SHIFT)
            | PAYLOAD_EMITTER_IN_LIST | PAYLOAD_DYNAMIC_EMITTER;
    for (uint model = MATERIAL_OPAQUE; model <= MATERIAL_DIELECTRIC; ++model) {
        failed |= cachedRoundTrip(allFlags | model, 1234.125, 1.5, 1.0);
        failed |= cachedRoundTrip(model, 0.03125, 1.333, 0.5);
        failed |= cachedRoundTrip(model, -1.0, 1.0, 0.125);
        failed |= cachedRoundTrip(model, 10000.0, 2.4, 0.0001);
        failed |= cachedRoundTrip(model, 1.0, 1.5, 0.0);
    }
    return failed;
}

export __extern_cpp uint testCachedFrostedTransmission() {
    Payload hit = {};
    packAlbedo(hit, float3(0.25, 0.5, 0.75));
    hit.flags = MATERIAL_DIELECTRIC | PAYLOAD_DIELECTRIC_ENTERING;
    hit.roughMetal = packHalf2(float2(0.25, 0.0));
    hit.iorTransmission = packHalf2(float2(1.5, 0.5));
    float3 restoredRd;
    Payload restored = unpackCachedPrimaryHit(
            packCachedPrimaryHit(float3(0.0, 0.0, 1.0), hit), restoredRd);
    float2 optical = unpackHalf2(restored.iorTransmission);
    Medium entered = makeDielectricMedium(false, optical.x, unpackAlbedo(restored), optical.y);
    // Beer-Lambert over one block must reproduce the authored tint times transmission factor.
    float3 transmitted = exp(-entered.extinction);
    return any(abs(transmitted - float3(0.125, 0.25, 0.375)) > float3(1.0e-5)) ? 1u : 0u;
}

export __extern_cpp uint testTerminalCelestialMis() {
    float3 n = float3(0.0, 0.0, 1.0);
    float lightPdf = celestialSquarePdf(n, n, 0.6 * PI / 180.0);
    float bsdfPdf = surfaceBsdfPdf(n, n, n, 0.001, 0.9, false);
    float terminal = surfaceNeeMisWeight(lightPdf, n, n, n, 0.001, 0.9, false, false);
    float continuing = surfaceNeeMisWeight(lightPdf, n, n, n, 0.001, 0.9, false, true);
    if (terminal != 1.0) return 1u;
    // Non-terminal vertices retain the complementary NEE/BSDF partition even for a tight GGX lobe.
    if (!(continuing > 0.0 && continuing < 0.01)) return 2u;
    return abs(continuing + powerHeuristic(bsdfPdf, lightPdf) - 1.0) > 1.0e-6 ? 4u : 0u;
}

export __extern_cpp uint testTerminalStaticEmitterMis() {
    float3 n = float3(0.0, 0.0, 1.0);
    float3 le = float3(1.0, 2.0, 3.0);
    float phatTerminal;
    float3 terminal = evalSampleContrib(n, -n, le, 0.25, float3(0.0), n, n, -n,
            float3(0.0), float3(1.0), 0.1, float3(1.0), false, false, 0.0, phatTerminal);
    // At normal incidence a pure F0=1 GGX lobe has f=1/(4*pi*alpha^2); the emitter area is 1/4.
    float3 expected = le * (1.0 / (16.0 * PI * 0.1 * 0.1));
    if (any(abs(terminal - expected) > expected * 1.0e-5)) return 1u;
    if (abs(phatTerminal - luminance(expected)) > luminance(expected) * 1.0e-5) return 2u;
    float phatContinuing;
    float3 continuing = evalSampleContrib(n, -n, le, 0.25, float3(0.0), n, n, -n,
            float3(0.0), float3(1.0), 0.1, float3(1.0), false, true, 0.0, phatContinuing);
    float wNee = powerHeuristic(staticEmitterMisPdf(1.0, 1.0),
            surfaceBsdfPdf(n, n, n, 0.1, surfaceSpecularProb(float3(0.0), float3(1.0), false), false));
    if (any(abs(continuing - terminal * wNee) > expected * 1.0e-5)) return 4u;
    return abs(phatContinuing - phatTerminal * wNee) > phatTerminal * 1.0e-5 ? 8u : 0u;
}
