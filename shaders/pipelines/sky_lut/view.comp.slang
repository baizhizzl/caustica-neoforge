// Sky-view LUT bake, once per frame. Renders the whole sky dome — atmosphere in-scatter with real
// multiple scattering — into a 192x108 latlong-ish table per celestial body, in the light-relative
// parameterisation from sky.slang. world.rmiss answers every miss with two texture fetches, avoiding a
// repeated atmosphere march on primary and GI-bounce rays.
//
// One slice per body (sun rows first, then moon), each marched with its own direction and illuminance.
// Sampling adds the two physical lighting solutions without crossfading between them.
//
// Reads the same WorldPush BDA slot the trace does, so the LUT and the frame it shades can never disagree
// about where the sun is.
import world_common;
import sky;
import bindings;

[[vk::push_constant]] PushAddr pcAddr;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= uint(SKY_VIEW_LUT_W) || tid.y >= uint(SKY_VIEW_LUT_TOTAL_H)) {
        return;
    }
    WorldPush push = ConstPtr<WorldPush>(pcAddr.worldPushAddr)[0];
    SkyState sky = skyState(push);

    int body = int(tid.y) / SKY_VIEW_LUT_H;
    int row = int(tid.y) - body * SKY_VIEW_LUT_H;
    bool isSun = body == SKY_VIEW_BODY_SUN;
    float3 worldLightDir = isSun ? sky.sunDir : sky.moonDir;
    float3 illuminance = float3(isSun ? sky.sunIlluminance : sky.moonIlluminance);
    float angularRadius = isSun ? sky.sunAngularRadius : sky.moonAngularRadius;

    // Sampling applies the half-texel correction; the bake must invert exactly that, so it works in the
    // same sub-UV space rather than in the parameterisation's raw [0,1].
    float2 uv = (float2(float(tid.x), float(row)) + 0.5)
              / float2(float(SKY_VIEW_LUT_W), float(SKY_VIEW_LUT_H));
    float viewZenithCos;
    float lightViewCos;
    skyViewLutParams(uv, sky.viewerRadiusKm, viewZenithCos, lightViewCos);

    float3 viewDir;
    float3 lightDir;
    skyViewLocalDirections(worldLightDir.y, viewZenithCos, lightViewCos, viewDir, lightDir);

    float3 origin = float3(0.0, sky.viewerRadiusKm, 0.0);
    float3 luminance = integrateScatteredLuminance(
            origin, viewDir, lightDir, illuminance, angularRadius,
            SKY_VIEW_STEPS, true, true,
            transmittanceLut, multiScatterLut, sky.groundAlbedo, true).luminance;

    // Horizon softening. The planet's surface is a real discontinuity in the model: a ray one arcminute
    // above the tangent direction escapes the atmosphere, and one arcminute below it terminates on the
    // ground a few km away. Measured at 370 m altitude under a 45 degree sun, that step is 9,977 to
    // 3,155 cd/m² — 1.7 EV across a fraction of a degree — and everything below it is a nearly constant
    // 2,400 cd/m² Lambertian plate. On screen that is a hard grey line across the distant view.
    //
    // Minecraft makes the discontinuity worse than it is on Earth. This ground is fictional: it is not the
    // world's terrain, which is what the player would actually see there. Beyond render distance the rays
    // simply escape, so the plate is drawn where a real view would show haze. And since the camera's
    // altitude tracks world Y, so a player at sea level sits within metres of the shell, where the ground
    // is reached almost immediately below the horizon and the in-scatter that would otherwise soften it
    // never accumulates.
    //
    // So fade the ground in over `horizonSoften` degrees of dip instead of switching to it. The value the
    // fade starts from is the ground-tangent direction marched with NO ground clip, which is exactly the
    // limit the rows above the horizon converge to, making both sides meet at the same value.
    float horizonCos = horizonZenithCos(sky.viewerRadiusKm);
    float dip = acos(clamp(viewZenithCos, -1.0, 1.0)) - acos(clamp(horizonCos, -1.0, 1.0));
    if (dip > 0.0 && sky.horizonSoften > 0.0) {
        float3 tangentDir;
        float3 tangentLight;
        skyViewLocalDirections(worldLightDir.y, horizonCos, lightViewCos, tangentDir, tangentLight);
        float3 skyward = integrateScatteredLuminance(
                origin, tangentDir, tangentLight, illuminance, angularRadius,
                SKY_VIEW_STEPS, true, true,
                transmittanceLut, multiScatterLut, sky.groundAlbedo, false).luminance;
        // Interpolate in LOG luminance, not linearly. The two ends can be 5 EV apart at a low sun, and a
        // linear blend spends most of its angular range near the bright end and then falls off a cliff in
        // the last few degrees — trading one hard edge for a slightly softer one. In log space the same
        // smoothstep spreads the drop evenly in stops, which is both what the eye integrates and what
        // attenuation through haze actually does (extinction is multiplicative, so it is linear in log).
        float3 low = log2(max(skyward, float3(1.0e-6)));
        float3 high = log2(max(luminance, float3(1.0e-6)));
        luminance = exp2(lerp(low, high, smoothstep(0.0, sky.horizonSoften, dip)));
    }

    skyViewImage[tid.xy] = float4(max(luminance, float3(0.0)), 1.0);
}
