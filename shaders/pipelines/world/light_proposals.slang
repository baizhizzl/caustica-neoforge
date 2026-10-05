// Power-weighted proposals and decoded emitter records shared by the light prepass and RIS.
import world_common;

public float lightProposalRandom(inout uint state) {
    state = state * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    uint bits = (word >> 22u) ^ word;
    return float(bits >> 8u) * (1.0 / 16777216.0);
}

public float unpackUnsignedFloat(uint bits, uint mantissaBits) {
    uint mantissaMask = (1u << mantissaBits) - 1u;
    uint mantissa = bits & mantissaMask;
    uint exponent = (bits >> mantissaBits) & 31u;
    if (exponent == 0u) {
        return float(mantissa) * exp2(float(1 - 15 - int(mantissaBits)));
    }
    return (1.0 + float(mantissa) / float(1u << mantissaBits))
            * exp2(float(int(exponent) - 15));
}

public float3 lightRadiance(Light light) {
    uint packed = light.le;
    return float3(unpackUnsignedFloat(packed & 0x7ffu, 6u),
            unpackUnsignedFloat((packed >> 11u) & 0x7ffu, 6u),
            unpackUnsignedFloat((packed >> 22u) & 0x3ffu, 5u));
}

// The half axes share three lanes two-at-a-time; see the Light record in world_common.
public float3 lightHalfU(Light light) {
    return float3(unpackHalf2(light.halfUxy), unpackHalf2(light.halfUzVx).x);
}

public float3 lightHalfV(Light light) {
    return float3(unpackHalf2(light.halfUzVx).y, unpackHalf2(light.halfVyz));
}

public void selectGlobalLight(uint64_t aliasAddr, uint lightCount, inout uint proposalSeed, out uint lightIndex) {
    float aliasSample = lightProposalRandom(proposalSeed) * float(lightCount);
    uint column = min(uint(aliasSample), lightCount - 1u);
    if (aliasAddr != 0) {
        LightAlias a = ConstPtr<LightAlias>(aliasAddr)[column];
        bool self = aliasSample - float(column) < a.accept;
        lightIndex = self ? column : a.aliasIndex;
    } else {
        lightIndex = column;
    }
}

public void selectSectionLight(uint64_t localAliasAddr, uint firstLight, uint lightCount, inout uint proposalSeed,
                        out uint lightIndex) {
    float aliasSample = lightProposalRandom(proposalSeed) * float(lightCount);
    uint column = min(uint(aliasSample), lightCount - 1u);
    LightAlias alias = ConstPtr<LightAlias>(localAliasAddr)[firstLight + column];
    bool self = aliasSample - float(column) < alias.accept;
    uint localIndex = self ? column : alias.aliasIndex;
    lightIndex = firstLight + localIndex;
}

public void selectLightGridSpanLight(uint64_t spanAddr, uint64_t localAliasAddr, LightGridCell cell, inout uint proposalSeed,
                              out uint lightIndex) {
    ConstPtr<LightGridSpan> spans = ConstPtr<LightGridSpan>(spanAddr);
    float aliasSample = lightProposalRandom(proposalSeed) * float(cell.spanCount);
    uint column = min(uint(aliasSample), cell.spanCount - 1u);
    LightGridSpan span = spans[cell.spanOffset + column];
    bool self = aliasSample - float(column) < span.accept;
    uint firstLight = self ? span.firstLight : span.aliasFirstLight;
    uint lightCount = self ? (span.packedLightCounts & 0xffffu)
            : (span.packedLightCounts >> 16u);
    selectSectionLight(localAliasAddr, firstLight, lightCount, proposalSeed, lightIndex);
}

public LightCandidate decodeLightCandidate(Light light, float inversePowerSum, bool hasAlias) {
    LightCandidate candidate = {};
    candidate.pos = light.pos;
    candidate.halfU = lightHalfU(light);
    candidate.halfV = lightHalfV(light);
    candidate.radiance = lightRadiance(light);
    float3 crossUV = cross(candidate.halfU, candidate.halfV);
    float crossLength = length(crossUV);
    candidate.area = 4.0 * crossLength;
    candidate.normal = crossLength > 0.0 ? crossUV / crossLength : float3(0.0);
    if ((light.section & 0x40000000u) != 0u) candidate.normal = -candidate.normal;
    candidate.section = light.section;
    candidate.power = max(0.0, candidate.area * dot(candidate.radiance, ACESCG_LUMA));
    candidate.globalPdf = hasAlias ? candidate.power * inversePowerSum : 0.0;
    return candidate;
}

// The origin/dimensions describe a bounded camera-centered region of the immutable light grid.
// Out-of-region secondary vertices retain the exact uncached local proposal.
public uint lightPoolCellOffset(int3 coord, WorldPush world) {
    int3 relative = coord - world.lightPoolOrigin.xyz;
    if (any(relative < int3(0)) || any(relative >= world.lightPoolDims.xyz)) return 0xffffffffu;
    uint linear = (uint(relative.z) * uint(world.lightPoolDims.y) + uint(relative.y))
            * uint(world.lightPoolDims.x) + uint(relative.x);
    return uint(world.lightPoolOrigin.w) + linear * uint(world.lightPoolDims.w);
}
