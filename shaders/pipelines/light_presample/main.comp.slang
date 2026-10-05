// Every entry is independently sampled from this frame's published light generation.
// Shared proposals do not cache visibility; RIS still casts its shadow ray against the current TLAS.
import world_common;
import light_proposals;

[[vk::push_constant]] LightPresamplePush pc;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= pc.entryCount) return;
    WorldPush world = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    uint seed = (id.x + 1u) * 0x9e3779b9u ^ (pc.frameIndex + 1u) * 0x85ebca6bu;
    uint index;
    if (id.x < uint(world.lightPoolOrigin.w)) {
        selectGlobalLight(pc.lightAliasAddr, world.lightCount, seed, index);
    } else {
        uint linear = (id.x - uint(world.lightPoolOrigin.w)) / uint(world.lightPoolDims.w);
        int3 relative = int3(int(linear % uint(world.lightPoolDims.x)),
                int((linear / uint(world.lightPoolDims.x)) % uint(world.lightPoolDims.y)),
                int(linear / uint(world.lightPoolDims.x * world.lightPoolDims.y)));
        int3 coord = world.lightPoolOrigin.xyz + relative;
        uint gridIndex = (uint(coord.z) * uint(world.lightGridDims.y) + uint(coord.y))
                * uint(world.lightGridDims.x) + uint(coord.x);
        LightGridCell cell = ConstPtr<LightGridCell>(pc.lightGridCellAddr)[gridIndex];
        if (cell.spanCount == 0u) {
            DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = LightCandidate();
            return;
        }
        selectLightGridSpanLight(pc.lightGridSpanAddr, pc.lightLocalAliasAddr, cell, seed, index);
    }
    LightCandidate candidate = decodeLightCandidate(ConstPtr<Light>(pc.lightBufAddr)[index],
            world.lightRebase.w, pc.lightAliasAddr != 0);
    DevicePtr<LightCandidate>(pc.lightPoolAddr)[id.x] = candidate;
}
