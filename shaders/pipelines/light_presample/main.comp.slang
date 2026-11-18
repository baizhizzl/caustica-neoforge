// Every entry is independently sampled from this frame's published light generation.
// Shared proposals do not cache visibility; RIS still casts its shadow ray against the current TLAS.
import world_common;
import light_proposals;

[[vk::push_constant]] LightPresamplePush pc;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= pc.entryCount || pc.lightPoolAddr == 0 || pc.worldPushAddr == 0) return;
    ConstPtr<WorldPush> wp = ConstPtr<WorldPush>(pc.worldPushAddr);
    uint lightCount = wp[0].lightCount;
    if (lightCount == 0u || pc.lightBufAddr == 0) {
        DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = LightCandidate();
        return;
    }
    uint seed = (id.x + 1u) * 0x9e3779b9u ^ (pc.frameIndex + 1u) * 0x85ebca6bu;
    uint index;
    int4 poolOrigin = wp[0].lightPoolOrigin;
    uint globalPoolCount = uint(max(poolOrigin.w, 0));
    if (id.x < globalPoolCount) {
        selectGlobalLight(pc.lightAliasAddr, lightCount, seed, index);
    } else {
        int4 poolDims = wp[0].lightPoolDims;
        int4 gridDims = wp[0].lightGridDims;
        if (poolDims.x <= 0 || poolDims.y <= 0 || poolDims.w <= 0
                || pc.lightGridCellAddr == 0 || pc.lightGridSpanAddr == 0 || pc.lightLocalAliasAddr == 0) {
            DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = LightCandidate();
            return;
        }
        uint linear = (id.x - globalPoolCount) / uint(poolDims.w);
        int3 relative = int3(int(linear % uint(poolDims.x)),
                int((linear / uint(poolDims.x)) % uint(poolDims.y)),
                int(linear / uint(poolDims.x * poolDims.y)));
        int3 coord = poolOrigin.xyz + relative;
        if (any(coord < int3(0)) || any(coord >= gridDims.xyz)) {
            DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = LightCandidate();
            return;
        }
        uint gridIndex = (uint(coord.z) * uint(gridDims.y) + uint(coord.y))
                * uint(gridDims.x) + uint(coord.x);
        LightGridCell cell = ConstPtr<LightGridCell>(pc.lightGridCellAddr)[gridIndex];
        if (cell.spanCount == 0u) {
            DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = LightCandidate();
            return;
        }
        selectLightGridSpanLight(pc.lightGridSpanAddr, pc.lightLocalAliasAddr, cell, seed, index);
    }
    if (index >= lightCount) {
        DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = LightCandidate();
        return;
    }
    LightCandidate candidate = decodeLightCandidate(ConstPtr<Light>(pc.lightBufAddr)[index],
            wp[0].lightRebase.w, pc.lightAliasAddr != 0);
    DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = candidate;
}
