package dev.comfyfluffy.caustica.rt;

/**
 * The renderer's scene-value unit convention.
 *
 * <p><b>A scene value is luminance in cd/m² (nits): {@code 1.0} = 1 cd/m².</b> This is a definition,
 * not a tuning knob — nothing here should ever be exposed as config. Light constants are authored
 * against it (sun ≈ 100,000 lux, full moon ≈ 0.25 lux, torch flame ≈ 15,000 cd/m²), which is what
 * makes them individually checkable against published photometric figures instead of only
 * meaningful relative to each other.
 *
 * <p>The renderer is RGB rather than spectral, so no radiometric-to-photometric conversion is
 * modelled: "luminance" means the AP1/D60 Y of the stored ACEScg triple, consistent with
 * {@code ACESCG_LUMA} in the metering shaders.
 *
 * <p>The EV100 metering scale, {@code RtLookPackage}'s sun/moon illuminance, block/star/night-sky
 * luminance, exposure curve, and shader-derived disc and atmosphere levels all use this convention.
 */
public final class RtSceneUnits {
    /** cd/m² that a scene value of {@code 1.0} represents. The unit definition; see class docs. */
    public static final float NITS_PER_UNIT = 1.0f;

    /**
     * Additive offset taking {@code log2(sceneValue)} to EV100, the standard photographic scale:
     * {@code EV100 = log2(L · S / K)} with ISO {@code S = 100} and reflected-light meter constant
     * {@code K = 12.5}, i.e. {@code EV100 = log2(8 · L)} for {@code L} in cd/m².
     *
     * <p>Metering and the exposure compensation curve both run on this scale so the curve's control
     * points can be read against published tables (sunny-16 ≈ EV 15, overcast ≈ EV 12, full moon
     * ≈ EV −3) rather than against a number meaningful only inside this codebase.
     */
    public static final float EV100_OFFSET = (float) (Math.log(8.0 * NITS_PER_UNIT) / Math.log(2.0));

    private RtSceneUnits() {
    }
}
