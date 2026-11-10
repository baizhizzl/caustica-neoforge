// Every entry is independently sampled from this frame's published light generation.
// Shared proposals do not cache visibility; RIS still casts its shadow ray against the current TLAS.
import world_common;
import light_proposals;

[[vk::push_constant]] LightPresamplePush pc;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= pc.entryCount || pc.lightPoolAddr == 0 || pc.worldPushAddr == 0) return;
    WorldPush world = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    if (world.lightCount == 0u || pc.lightBufAddr == 0) {
        DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = LightCandidate();
        return;
    }
    uint seed = (id.x + 1u) * 0x9e3779b9u ^ (pc.frameIndex + 1u) * 0x85ebca6bu;
    uint index;
    uint globalPoolCount = uint(max(world.lightPoolOrigin.w, 0));
    if (id.x < globalPoolCount) {
        selectGlobalLight(pc.lightAliasAddr, world.lightCount, seed, index);
    } else {
        if (world.lightPoolDims.x <= 0 || world.lightPoolDims.y <= 0 || world.lightPoolDims.w <= 0
                || pc.lightGridCellAddr == 0 || pc.lightGridSpanAddr == 0 || pc.lightLocalAliasAddr == 0) {
            DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = LightCandidate();
            return;
        }
        uint linear = (id.x - globalPoolCount) / uint(world.lightPoolDims.w);
        int3 relative = int3(int(linear % uint(world.lightPoolDims.x)),
                int((linear / uint(world.lightPoolDims.x)) % uint(world.lightPoolDims.y)),
                int(linear / uint(world.lightPoolDims.x * world.lightPoolDims.y)));
        int3 coord = world.lightPoolOrigin.xyz + relative;
        if (any(coord < int3(0)) || any(coord >= world.lightGridDims.xyz)) {
            DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = LightCandidate();
            return;
        }
        uint gridIndex = (uint(coord.z) * uint(world.lightGridDims.y) + uint(coord.y))
                * uint(world.lightGridDims.x) + uint(coord.x);
        LightGridCell cell = ConstPtr<LightGridCell>(pc.lightGridCellAddr)[gridIndex];
        if (cell.spanCount == 0u) {
            DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = LightCandidate();
            return;
        }
        selectLightGridSpanLight(pc.lightGridSpanAddr, pc.lightLocalAliasAddr, cell, seed, index);
    }
    if (index >= world.lightCount) {
        DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = LightCandidate();
        return;
    }
    LightCandidate candidate = decodeLightCandidate(ConstPtr<Light>(pc.lightBufAddr)[index],
            world.lightRebase.w, pc.lightAliasAddr != 0);
    DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = candidate;
}
