package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.rt.light.RtDynamicLightSpecs.Candidate;
import dev.comfyfluffy.caustica.rt.light.RtDynamicLightSpecs.EmitterProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class RtDynamicLightSpecsTest {
    @Test
    void classifiesVanillaAndModdedItemsBySpectrumAndLightLevel() {
        EmitterProfile torch = RtDynamicLightSpecs.classifyItem("torch", 14);
        assertNotNull(torch);
        assertEquals(14, torch.lightLevel());
        assertTrue(torch.flickers());
        assertTrue(torch.srgbR() > torch.srgbB());

        EmitterProfile soulTorch = RtDynamicLightSpecs.classifyItem("soul_torch", 10);
        assertNotNull(soulTorch);
        assertEquals(10, soulTorch.lightLevel());
        assertTrue(soulTorch.srgbB() > soulTorch.srgbR());

        EmitterProfile copperTorch = RtDynamicLightSpecs.classifyItem("copper_torch", 14);
        assertNotNull(copperTorch);
        assertTrue(copperTorch.srgbG() > copperTorch.srgbR());

        EmitterProfile lavaBucket = RtDynamicLightSpecs.classifyItem("lava_bucket", 0);
        assertNotNull(lavaBucket);
        assertEquals(15, lavaBucket.lightLevel());
        assertTrue(lavaBucket.flickers());

        EmitterProfile moddedLamp = RtDynamicLightSpecs.classifyItem("arcane_brass_lamp", 15);
        assertNotNull(moddedLamp);
        assertEquals(15, moddedLamp.lightLevel());

        assertNull(RtDynamicLightSpecs.classifyItem("iron_sword", 0));
        assertNull(RtDynamicLightSpecs.classifyItem("cobblestone", 0));
    }

    @Test
    void cyanSourcesWinOverTheGenericWarmLanternFamily() {
        for (String path : List.of("sea_lantern", "conduit", "sea_pickle", "sculk_catalyst")) {
            EmitterProfile profile = RtDynamicLightSpecs.classifyItem(path, 15);
            assertNotNull(profile, path);
            assertTrue(profile.srgbB() > profile.srgbR(), path + " must be cyan-blue");
            assertFalse(profile.flickers(), path + " must be steady");
        }
        EmitterProfile lantern = RtDynamicLightSpecs.classifyItem("lantern", 15);
        assertNotNull(lantern);
        assertTrue(lantern.srgbR() > lantern.srgbB());
        assertTrue(lantern.flickers());
    }

    @Test
    void blockLightEmissionGatesStateDependentItems() {
        EmitterProfile litCandle = RtDynamicLightSpecs.classifyItem("candle", 14);
        assertNotNull(litCandle);
        assertEquals(14, litCandle.lightLevel());
        assertTrue(litCandle.flickers());
        assertNull(RtDynamicLightSpecs.classifyItem("candle", 0));

        EmitterProfile litBulb = RtDynamicLightSpecs.classifyItem("copper_bulb", 15);
        assertNotNull(litBulb);
        assertEquals(15, litBulb.lightLevel());
        assertTrue(litBulb.srgbG() > litBulb.srgbR());
        assertNull(RtDynamicLightSpecs.classifyItem("copper_bulb", 0));

        EmitterProfile chargedAnchor = RtDynamicLightSpecs.classifyItem("respawn_anchor", 10);
        assertNotNull(chargedAnchor);
        assertEquals(10, chargedAnchor.lightLevel());
        assertTrue(chargedAnchor.srgbB() > chargedAnchor.srgbG());
        assertNull(RtDynamicLightSpecs.classifyItem("respawn_anchor", 0));

        assertNull(RtDynamicLightSpecs.classifyItem("light", 0));
        EmitterProfile litLight = RtDynamicLightSpecs.classifyItem("light", 15);
        assertNotNull(litLight);
        assertEquals(15, litLight.lightLevel());
    }

    @Test
    void classifiesLuminousAndBurningEntities() {
        EmitterProfile blaze = RtDynamicLightSpecs.classifyEntity("blaze", true, 0);
        assertNotNull(blaze);
        assertEquals(15, blaze.lightLevel());
        assertTrue(blaze.flickers());

        EmitterProfile glowSquid = RtDynamicLightSpecs.classifyEntity("glow_squid", false, 0);
        assertNotNull(glowSquid);
        assertFalse(glowSquid.flickers());
        assertTrue(glowSquid.srgbG() > glowSquid.srgbR());

        EmitterProfile burningZombie = RtDynamicLightSpecs.classifyEntity("zombie", true, 0);
        assertNotNull(burningZombie);
        assertEquals(14, burningZombie.lightLevel());

        assertNull(RtDynamicLightSpecs.classifyEntity("zombie", false, 0));
    }

    @Test
    void acesCgIntensityScalesMonotonicallyAndRemainsWithinHalfFloatCeiling() {
        EmitterProfile dim = RtDynamicLightSpecs.classifyItem("redstone_torch", 7);
        EmitterProfile bright = RtDynamicLightSpecs.classifyItem("lantern", 15);
        assertNotNull(dim);
        assertNotNull(bright);

        float[] dimAces = RtDynamicLightSpecs.toAcesCgIntensity(dim, 2000.0f, 1.0f, 1.0f);
        float[] brightAces = RtDynamicLightSpecs.toAcesCgIntensity(bright, 2000.0f, 1.0f, 1.0f);
        float dimLuma = RtDynamicLightSpecs.acesLuminance(dimAces[0], dimAces[1], dimAces[2]);
        float brightLuma = RtDynamicLightSpecs.acesLuminance(brightAces[0], brightAces[1], brightAces[2]);
        assertTrue(brightLuma > dimLuma);

        // The allocation-free writer must match the convenience wrapper exactly.
        float[] scratch = new float[3];
        float scratchLuma = RtDynamicLightSpecs.writeAcesCgIntensity(bright, 2000.0f, 1.0f, 1.0f, scratch);
        assertArrayEquals(brightAces, scratch, 1.0e-5f);
        assertEquals(brightLuma, scratchLuma, 1.0e-4f);

        EmitterProfile lightning = RtDynamicLightSpecs.classifyEntity("lightning_bolt", false, 0);
        assertNotNull(lightning);
        float[] extreme = RtDynamicLightSpecs.toAcesCgIntensity(lightning, 20000.0f, 4.0f, 1.11f);
        for (float channel : extreme) {
            assertTrue(Float.isFinite(channel) && channel >= 0.0f && channel <= 65504.0f);
        }
    }

    @Test
    void halfFloatClampScalesAllChannelsEquallyAndPreservesChroma() {
        // A warm near-white emitter pushed hard enough that every ACEScg channel overflows half-float.
        EmitterProfile saturated = new EmitterProfile(1.0f, 0.85f, 0.65f, 15, 5.0f, 0.2f, false);
        float[] reference = RtDynamicLightSpecs.toAcesCgIntensity(saturated, 2000.0f, 1.0f, 1.0f);
        float[] clamped = RtDynamicLightSpecs.toAcesCgIntensity(saturated, 20000.0f, 4.0f, 1.11f);
        float max = Math.max(clamped[0], Math.max(clamped[1], clamped[2]));
        assertEquals(65504.0f, max, 0.5f, "peak channel must reach the half-float ceiling");
        // Per-channel clamping would collapse multiple channels onto 65504 and whiten the light; equal
        // scaling must leave every pairwise ratio (the ACEScg chromaticity) untouched.
        assertEquals(reference[1] / reference[0], clamped[1] / clamped[0], 1.0e-4f);
        assertEquals(reference[2] / reference[0], clamped[2] / clamped[0], 1.0e-4f);
    }

    @Test
    void flameFlickerIsBoundedAndDecorrelatedAcrossSources() {
        for (int step = 0; step < 200; step++) {
            double t = step * 0.033;
            float f1 = RtDynamicLightSpecs.flameFlicker(t, 101L);
            float f2 = RtDynamicLightSpecs.flameFlicker(t, 202L);
            assertTrue(f1 >= 0.89f && f1 <= 1.11f);
            assertTrue(f2 >= 0.89f && f2 <= 1.11f);
        }
        assertNotEquals(
                RtDynamicLightSpecs.flameFlicker(1.25, 11L),
                RtDynamicLightSpecs.flameFlicker(1.25, 99L));
    }

    @Test
    void shadowGroupBitsAreDistinctForSixOwnersAndUnassignedBeyondThem() {
        Set<Integer> bits = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            int bit = RtDynamicLightSpecs.shadowGroupBit(i);
            assertTrue(bit >= 0x04 && bit <= 0x80);
            assertEquals(1, Integer.bitCount(bit));
            assertTrue(bits.add(bit));
        }
        assertEquals(6, bits.size());
        // Owners past the sixth group get no bit instead of wrapping onto an owner already using one.
        assertEquals(0, RtDynamicLightSpecs.shadowGroupBit(6));
        assertEquals(0, RtDynamicLightSpecs.shadowGroupBit(8));
        assertEquals(0, RtDynamicLightSpecs.shadowGroupBit(-1));
    }

    @Test
    void thirdPersonHandOffsetFollowsBodyYawClampsPitchAndScalesWithEntitySize() {
        double[] right = new double[3];
        double[] left = new double[3];
        RtDynamicLightSpecs.handOffset(90.0f, 0.0f, 1.0, 1.0f, right);
        RtDynamicLightSpecs.handOffset(90.0f, 0.0f, -1.0, 1.0f, left);
        // Facing +Z with yaw 90 looks toward -X; the main hand sits toward -Z and the off hand toward +Z.
        assertEquals(-0.46, right[0], 1.0e-5);
        assertEquals(-0.34, right[2], 1.0e-5);
        assertEquals(-0.18, right[1], 1.0e-5);
        assertEquals(0.34, left[2], 1.0e-5);

        // Extreme look up/down is clamped to +/-20 degrees by the pure geometry helper.
        double[] down = new double[3];
        double[] clamped = new double[3];
        RtDynamicLightSpecs.handOffset(0.0f, -90.0f, 1.0, 1.0f, down);
        RtDynamicLightSpecs.handOffset(0.0f, -20.0f, 1.0, 1.0f, clamped);
        assertArrayEquals(clamped, down, 1.0e-5);
        assertTrue(down[1] > -0.35 && down[1] < 0.0);

        double[] scaled = new double[3];
        RtDynamicLightSpecs.handOffset(90.0f, 0.0f, 1.0, 2.0f, scaled);
        assertEquals(right[0] * 2.0, scaled[0], 1.0e-5);
        assertEquals(right[1] * 2.0, scaled[1], 1.0e-5);
        assertEquals(right[2] * 2.0, scaled[2], 1.0e-5);
    }

    @Test
    void mouthOffsetIsCentredOnTheHeadWithoutHandLateralOffset() {
        double[] mouth = new double[3];
        RtDynamicLightSpecs.mouthOffset(90.0f, 0.0f, 1.0f, mouth);
        // Facing -X at yaw 90: 0.30 forward of the eyes, 0.06 below, with no right-hand side offset.
        assertEquals(-0.30, mouth[0], 1.0e-5);
        assertEquals(-0.06, mouth[1], 1.0e-5);
        assertEquals(0.0, mouth[2], 1.0e-5);
    }

    @Test
    void candidateSelectionKeepsHighestPriorityAndStabilizesOrderByKey() {
        List<Candidate> all = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            // Candidate keys 100..123 with priority increasing with i so keys 108..123 win.
            all.add(new Candidate(i, 64.0, 0.0, 0.1f, 100.0f, 80.0f, 50.0f,
                    RtDynamicLightSpecs.NO_ENTITY, i * 10.0f, 100L + i));
        }
        List<Candidate> top = RtDynamicLightSpecs.selectTopCandidates(all, RtDynamicLightSpecs.MAX_DYNAMIC_LIGHTS);
        assertEquals(RtDynamicLightSpecs.MAX_DYNAMIC_LIGHTS, top.size());
        assertEquals(108L, top.getFirst().stableKey());
        assertEquals(123L, top.getLast().stableKey());
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i - 1).stableKey() < top.get(i).stableKey());
        }
        assertEquals(24, all.size(), "test input must not be mutated");
    }
}
