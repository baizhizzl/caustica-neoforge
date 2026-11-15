package dev.comfyfluffy.caustica.rt.light;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pure-CPU photometric classification, ACEScg colour conversion, temporal flame modulation, and
 * importance-based candidate selection for dynamic spherical area lights.
 */
public final class RtDynamicLightSpecs {
    /** Matches {@code WorldPush.dynamicLights} capacity in {@code world_common.slang}. */
    public static final int MAX_DYNAMIC_LIGHTS = 16;

    /** Owner id for emitters that are not carried by an entity mesh (e.g. ambient lightning). */
    public static final int NO_ENTITY = Integer.MIN_VALUE;

    /** Number of independent hardware dynamic-shadow groups exposed as mask bits 0x04..0x80. */
    public static final int SHADOW_GROUP_BITS = 6;
    /** Mask bit of the first hardware dynamic-shadow group; each later group is one bit higher. */
    private static final int SHADOW_GROUP_BIT_BASE = 0x04;

    /** Largest finite value representable by the half-float light lanes downstream. */
    private static final float HALF_FLOAT_MAX = 65504.0f;

    // Linear Rec.709 (D65) -> ACEScg (AP1/D60), matching world_common.slang and RtLightCollector.
    private static final float BT709_TO_ACES_R0 = 0.61309743f;
    private static final float BT709_TO_ACES_R1 = 0.33952314f;
    private static final float BT709_TO_ACES_R2 = 0.04737945f;
    private static final float BT709_TO_ACES_G0 = 0.07019372f;
    private static final float BT709_TO_ACES_G1 = 0.91635388f;
    private static final float BT709_TO_ACES_G2 = 0.01345240f;
    private static final float BT709_TO_ACES_B0 = 0.02061559f;
    private static final float BT709_TO_ACES_B1 = 0.10956977f;
    private static final float BT709_TO_ACES_B2 = 0.86981463f;
    private static final float ACESCG_LUMA_R = 0.27222872f;
    private static final float ACESCG_LUMA_G = 0.67408177f;
    private static final float ACESCG_LUMA_B = 0.05368952f;

    /** Luminous intensity scale (cd per cd/m² baseline) for a level-15 spherical dynamic emitter. */
    private static final float FULL_STRENGTH_CANDELA_PER_CDM2 = 0.36f;

    /** A bare glow item frame is a wall-mounted glow item with a soft, low-level ambient contribution. */
    private static final EmitterProfile GLOW_ITEM_FRAME_PROFILE =
            new EmitterProfile(0.62f, 0.94f, 1.00f, 7, 1.0f, 0.14f, false);

    private RtDynamicLightSpecs() {
    }

    /**
     * Optical and geometric properties of a dynamic light source before world placement.
     *
     * @param srgbR      authored sRGB red tint in [0, 1]
     * @param srgbG      authored sRGB green tint in [0, 1]
     * @param srgbB      authored sRGB blue tint in [0, 1]
     * @param lightLevel vanilla-equivalent light level in [1, 15]
     * @param boost      additional photometric multiplier (e.g. lightning flashes)
     * @param radius     spherical area-light radius in blocks (controls soft-shadow penumbrae)
     * @param flickers   whether organic multi-harmonic flame modulation applies
     */
    public record EmitterProfile(
            float srgbR,
            float srgbG,
            float srgbB,
            int lightLevel,
            float boost,
            float radius,
            boolean flickers
    ) {
    }

    /**
     * World-space candidate emitter prior to rebasing and GPU packing.
     *
     * @param ownerEntityId entity whose mesh hosts this emitter, or {@link #NO_ENTITY}
     */
    public record Candidate(
            double worldX,
            double worldY,
            double worldZ,
            float radius,
            float acesR,
            float acesG,
            float acesB,
            int ownerEntityId,
            float priorityScore,
            long stableKey
    ) {
    }

    private static final Comparator<Candidate> PRIORITY_ORDER = (a, b) -> {
        int byScore = Float.compare(b.priorityScore, a.priorityScore);
        return byScore != 0 ? byScore : Long.compare(a.stableKey, b.stableKey);
    };

    private static final Comparator<Candidate> STABLE_KEY_ORDER =
            Comparator.comparingLong(Candidate::stableKey);

    /** Profile used by a glow item frame that holds no other luminous item. */
    public static EmitterProfile glowItemFrameProfile() {
        return GLOW_ITEM_FRAME_PROFILE;
    }

    /**
     * Classify a held or dropped item from its registry path and optional block-state light emission.
     * Returns {@code null} when the item does not emit light.
     */
    public static EmitterProfile classifyItem(String path, int blockLightEmission) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String id = path.toLowerCase(Locale.ROOT);

        if (blockLightEmission > 0) {
            int level = Math.clamp(blockLightEmission, 1, 15);
            if (id.contains("soul_")) {
                return new EmitterProfile(0.28f, 0.84f, 1.00f, level, 1.15f,
                        id.contains("lantern") ? 0.13f : 0.09f, true);
            }
            if (id.contains("copper_torch") || id.contains("copper_lantern") || id.contains("copper_bulb")) {
                boolean flame = id.contains("torch") || id.contains("lantern");
                return new EmitterProfile(0.36f, 0.96f, 0.58f, level, 1.05f,
                        flame ? 0.10f : 0.22f, flame);
            }
            if (id.contains("redstone")) {
                return new EmitterProfile(1.00f, 0.18f, 0.10f, level, 1.25f, 0.10f, false);
            }
            if (id.contains("torch") || id.contains("candle")) {
                return new EmitterProfile(1.00f, 0.60f, 0.24f, level, 1.05f, 0.09f, true);
            }
            // Cyan sources must win over the generic warm "lantern" family below.
            if (id.contains("sea_lantern") || id.contains("conduit") || id.contains("sea_pickle")
                    || id.contains("sculk")) {
                return new EmitterProfile(0.50f, 0.93f, 0.98f, level, 1.0f, 0.22f, false);
            }
            if (id.contains("lantern") || id.contains("campfire") || id.contains("jack_o_lantern")) {
                return new EmitterProfile(1.00f, 0.64f, 0.28f, level, 1.05f, 0.15f, true);
            }
            if (id.contains("amethyst") || id.contains("crying_obsidian") || id.contains("respawn_anchor")
                    || id.contains("portal")) {
                return new EmitterProfile(0.76f, 0.40f, 1.00f, level, 1.15f, 0.18f, false);
            }
            if (id.contains("pearlescent_froglight") || id.contains("end_rod") || id.contains("beacon")) {
                return new EmitterProfile(0.96f, 0.90f, 1.00f, level, 1.0f, 0.20f, false);
            }
            if (id.contains("verdant_froglight")) {
                return new EmitterProfile(0.60f, 1.00f, 0.64f, level, 1.0f, 0.22f, false);
            }
            if (id.contains("lava") || id.contains("magma")) {
                return new EmitterProfile(1.00f, 0.46f, 0.14f, level, 1.05f, 0.22f, true);
            }
            if (id.contains("glowstone") || id.contains("shroomlight") || id.contains("ochre_froglight")
                    || id.contains("cave_vines")) {
                return new EmitterProfile(1.00f, 0.78f, 0.44f, level, 1.0f, 0.22f, false);
            }
            return new EmitterProfile(1.00f, 0.86f, 0.68f, level, 1.0f, 0.18f, false);
        }

        // Non-block luminous items.
        return switch (id) {
            case "lava_bucket" -> new EmitterProfile(1.00f, 0.46f, 0.14f, 15, 1.05f, 0.18f, true);
            case "blaze_rod", "fire_charge" -> new EmitterProfile(1.00f, 0.56f, 0.18f, 12, 1.05f, 0.11f, true);
            case "blaze_powder", "magma_cream" -> new EmitterProfile(1.00f, 0.50f, 0.16f, 9, 1.0f, 0.10f, true);
            case "nether_star" -> new EmitterProfile(0.90f, 0.95f, 1.00f, 15, 1.15f, 0.16f, false);
            case "glowstone_dust", "spectral_arrow" -> new EmitterProfile(1.00f, 0.80f, 0.42f, 10, 1.0f, 0.10f, false);
            case "glow_ink_sac", "prismarine_crystals", "heart_of_the_sea" ->
                    new EmitterProfile(0.36f, 0.94f, 0.90f, 11, 1.05f, 0.12f, false);
            case "glow_berries" -> new EmitterProfile(1.00f, 0.76f, 0.36f, 12, 1.0f, 0.11f, false);
            case "totem_of_undying", "enchanted_golden_apple" ->
                    new EmitterProfile(1.00f, 0.85f, 0.46f, 10, 1.0f, 0.12f, false);
            case "ender_eye", "dragon_breath", "echo_shard" ->
                    new EmitterProfile(0.42f, 0.84f, 0.92f, 9, 1.05f, 0.11f, false);
            default -> null;
        };
    }

    /**
     * Classify an intrinsic or burning entity emitter by entity-type registry path.
     * Returns {@code null} when the entity itself is non-emissive.
     */
    public static EmitterProfile classifyEntity(String entityPath, boolean onFire, int containedBlockLight) {
        if (entityPath != null && !entityPath.isEmpty()) {
            String id = entityPath.toLowerCase(Locale.ROOT);
            switch (id) {
                case "lightning_bolt":
                    return new EmitterProfile(0.78f, 0.88f, 1.00f, 15, 5.0f, 0.55f, true);
                case "blaze":
                    return new EmitterProfile(1.00f, 0.56f, 0.16f, 15, 1.15f, 0.28f, true);
                case "glow_squid":
                    return new EmitterProfile(0.30f, 0.96f, 0.88f, 13, 1.15f, 0.26f, false);
                case "magma_cube":
                    return new EmitterProfile(1.00f, 0.45f, 0.14f, 12, 1.05f, 0.26f, true);
                case "allay", "vex":
                    return new EmitterProfile(0.44f, 0.88f, 1.00f, 11, 1.05f, 0.18f, false);
                case "end_crystal":
                    return new EmitterProfile(0.96f, 0.48f, 1.00f, 14, 1.15f, 0.30f, false);
                case "dragon_fireball":
                    return new EmitterProfile(0.84f, 0.32f, 1.00f, 14, 1.15f, 0.22f, true);
                case "fireball", "small_fireball":
                    return new EmitterProfile(1.00f, 0.54f, 0.16f, 14, 1.10f, 0.18f, true);
                case "wither_skull":
                    return new EmitterProfile(0.40f, 0.74f, 1.00f, 11, 1.05f, 0.16f, false);
                case "spectral_arrow":
                    return new EmitterProfile(1.00f, 0.84f, 0.38f, 11, 1.0f, 0.09f, false);
                case "firework_rocket":
                    return new EmitterProfile(1.00f, 0.76f, 0.40f, 13, 1.10f, 0.14f, true);
                case "tnt":
                    return new EmitterProfile(1.00f, 0.68f, 0.28f, 12, 1.0f, 0.24f, true);
                case "experience_orb":
                    return new EmitterProfile(0.68f, 1.00f, 0.34f, 8, 0.9f, 0.08f, false);
                case "eye_of_ender":
                    return new EmitterProfile(0.45f, 0.95f, 0.78f, 10, 1.0f, 0.10f, false);
                default:
                    break;
            }
        }
        if (containedBlockLight > 0) {
            return new EmitterProfile(1.00f, 0.78f, 0.48f, Math.clamp(containedBlockLight, 1, 15),
                    1.0f, 0.25f, false);
        }
        if (onFire) {
            return new EmitterProfile(1.00f, 0.54f, 0.18f, 14, 1.05f, 0.26f, true);
        }
        return null;
    }

    /**
     * Smooth, bounded multi-harmonic flame modulation in [0.89, 1.11], seeded per emitter key so multiple
     * torches do not pulse in unison.
     */
    public static float flameFlicker(double timeSeconds, long stableKey) {
        long h = stableKey * 0x9E3779B97F4A7C15L;
        h ^= h >>> 30;
        double phase = ((h & 0xFFFFL) / 65535.0) * (Math.PI * 2.0);
        double wave = 0.055 * Math.sin(timeSeconds * 6.7 + phase)
                + 0.035 * Math.sin(timeSeconds * 13.9 + phase * 2.1 + 1.3)
                + 0.020 * Math.sin(timeSeconds * 23.3 + phase * 0.7 + 2.7);
        return (float) Math.clamp(1.0 + wave, 0.89, 1.11);
    }

    /** Frame-rate-independent exponential smoothing factor in (0, 1]. */
    public static float smoothingAlpha(float dtSeconds, float tauSeconds) {
        if (tauSeconds <= 1.0e-4f || !Float.isFinite(dtSeconds) || dtSeconds <= 0.0f) {
            return 1.0f;
        }
        return (float) Math.clamp(1.0 - Math.exp(-dtSeconds / tauSeconds), 0.01, 1.0);
    }

    /**
     * Convert an {@link EmitterProfile} into scene-linear ACEScg radiant intensity (cd = lm/sr) and write
     * the three channels into {@code out}, returning their ACEScg luminance. The output array lets the
     * per-frame collector avoid a fresh {@code float[3]} for every candidate.
     */
    public static float writeAcesCgIntensity(EmitterProfile profile, float baselineLuminanceCdM2,
                                             float userIntensity, float flickerMultiplier, float[] out) {
        float linR = srgbToLinear(Math.clamp(profile.srgbR, 0.0f, 1.0f));
        float linG = srgbToLinear(Math.clamp(profile.srgbG, 0.0f, 1.0f));
        float linB = srgbToLinear(Math.clamp(profile.srgbB, 0.0f, 1.0f));

        float acesR = BT709_TO_ACES_R0 * linR + BT709_TO_ACES_R1 * linG + BT709_TO_ACES_R2 * linB;
        float acesG = BT709_TO_ACES_G0 * linR + BT709_TO_ACES_G1 * linG + BT709_TO_ACES_G2 * linB;
        float acesB = BT709_TO_ACES_B0 * linR + BT709_TO_ACES_B1 * linG + BT709_TO_ACES_B2 * linB;

        float luma = Math.max(ACESCG_LUMA_R * acesR + ACESCG_LUMA_G * acesG + ACESCG_LUMA_B * acesB, 1.0e-4f);
        float levelNorm = Math.clamp(profile.lightLevel, 1, 15) / 15.0f;
        float targetCandelas = Math.max(baselineLuminanceCdM2, 1.0f)
                * FULL_STRENGTH_CANDELA_PER_CDM2
                * (levelNorm * levelNorm)
                * Math.max(profile.boost, 0.0f)
                * Math.max(userIntensity, 0.0f)
                * Math.max(flickerMultiplier, 0.0f);
        float scale = targetCandelas / luma;
        float scaledR = acesR * scale;
        float scaledG = acesG * scale;
        float scaledB = acesB * scale;
        // Half-float transport saturates at 65504. Clamping each channel independently would map multiple
        // bright channels to the same ceiling and visibly desaturate a coloured light, so scale all three
        // by the same factor: ACEScg chromaticity is preserved and only intensity is reduced.
        float maxC = Math.max(scaledR, Math.max(scaledG, scaledB));
        if (maxC > HALF_FLOAT_MAX) {
            float clampScale = HALF_FLOAT_MAX / maxC;
            scaledR *= clampScale;
            scaledG *= clampScale;
            scaledB *= clampScale;
        }
        out[0] = scaledR;
        out[1] = scaledG;
        out[2] = scaledB;
        return acesLuminance(out[0], out[1], out[2]);
    }

    /**
     * Convert an {@link EmitterProfile} into scene-linear ACEScg radiant intensity (cd = lm/sr),
     * normalized by chromaticity luminance so deeply coloured lights (soul fire, redstone) retain full
     * photometric reach for their light level.
     */
    public static float[] toAcesCgIntensity(EmitterProfile profile, float baselineLuminanceCdM2,
                                            float userIntensity, float flickerMultiplier) {
        float[] out = new float[3];
        writeAcesCgIntensity(profile, baselineLuminanceCdM2, userIntensity, flickerMultiplier, out);
        return out;
    }

    /** Sort candidates from highest to lowest importance (ties broken by stable key). */
    public static void sortCandidatesByPriority(List<Candidate> candidates) {
        candidates.sort(PRIORITY_ORDER);
    }

    /** Order the first {@code count} candidates by stable key so GPU slot indices never permute. */
    public static void sortCandidatesByStableKey(List<Candidate> candidates, int count) {
        if (count > 1) {
            candidates.subList(0, count).sort(STABLE_KEY_ORDER);
        }
    }

    /**
     * Select up to {@code maxCount} highest-priority candidates, then order the survivors by stable key
     * so GPU slot indices do not permute frame-to-frame as distances cross each other.
     */
    public static List<Candidate> selectTopCandidates(List<Candidate> candidates, int maxCount) {
        int cap = Math.clamp(maxCount, 0, MAX_DYNAMIC_LIGHTS);
        if (cap == 0 || candidates.isEmpty()) {
            return List.of();
        }
        List<Candidate> copy = new ArrayList<>(candidates);
        sortCandidatesByPriority(copy);
        int count = Math.min(cap, copy.size());
        sortCandidatesByStableKey(copy, count);
        return count == copy.size() ? copy : new ArrayList<>(copy.subList(0, count));
    }

    public static float acesLuminance(float acesR, float acesG, float acesB) {
        return ACESCG_LUMA_R * acesR + ACESCG_LUMA_G * acesG + ACESCG_LUMA_B * acesB;
    }

    /**
     * Hardware TLAS shadow-group bit for the {@code groupIndex}-th distinct emitter-owning entity.
     * Six groups occupy mask bits 2..7. Owners beyond the last group get no bit at all: wrapping would
     * make two unrelated owners share one hardware bit and cull each other's shadows.
     */
    public static int shadowGroupBit(int groupIndex) {
        if (groupIndex < 0 || groupIndex >= SHADOW_GROUP_BITS) {
            return 0;
        }
        return SHADOW_GROUP_BIT_BASE << groupIndex;
    }

    /**
     * Offset from the eye to a held light in the body's local frame. Third-person and creature poses use
     * the interpolated body yaw with a clamped pitch, so looking down never drives the light into the feet.
     * Results are written to {@code out} to keep the per-frame collector allocation-free.
     */
    public static void handOffset(float yawDeg, float pitchDeg, double sideSign, float scale, double[] out) {
        float clampedPitch = Math.clamp(pitchDeg, -20.0f, 20.0f);
        float yawRad = yawDeg * (float) Math.PI / 180.0f;
        float pitchRad = clampedPitch * (float) Math.PI / 180.0f;
        float cosPitch = (float) Math.cos(pitchRad);
        double forwardX = -Math.sin(yawRad) * cosPitch;
        double forwardY = -Math.sin(pitchRad);
        double forwardZ = Math.cos(yawRad) * cosPitch;
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);
        double reach = 0.46 * scale;
        double lateral = 0.34 * sideSign * scale;
        double drop = 0.18 * scale;
        out[0] = forwardX * reach + rightX * lateral;
        out[1] = forwardY * reach - drop;
        out[2] = forwardZ * reach + rightZ * lateral;
    }

    /**
     * Offset from the eye to a mouth- or centre-carried light on a non-humanoid creature (fox, wolf,
     * dolphin, allay, …). Quadrupeds and aquatic mobs hold luminous items in front of the head rather
     * than off to one side, so no lateral hand offset applies. Results are written to {@code out} to keep
     * the per-frame collector allocation-free.
     */
    public static void mouthOffset(float yawDeg, float pitchDeg, float scale, double[] out) {
        float clampedPitch = Math.clamp(pitchDeg, -20.0f, 20.0f);
        float yawRad = yawDeg * (float) Math.PI / 180.0f;
        float pitchRad = clampedPitch * (float) Math.PI / 180.0f;
        float cosPitch = (float) Math.cos(pitchRad);
        double forwardX = -Math.sin(yawRad) * cosPitch;
        double forwardY = -Math.sin(pitchRad);
        double forwardZ = Math.cos(yawRad) * cosPitch;
        double reach = 0.30 * scale;
        double rise = -0.06 * scale;
        out[0] = forwardX * reach;
        out[1] = forwardY * reach + rise;
        out[2] = forwardZ * reach;
    }

    private static float srgbToLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
