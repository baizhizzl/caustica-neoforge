#pragma once

#include "nvsdk_ngx_defs.h"
#include "nvsdk_ngx_defs_dlssd.h"

#include <array>

namespace caustica {

static_assert(NVSDK_NGX_RayReconstruction_Hint_Render_Preset_F == 6,
        "RR2 preset must match the Java option");

inline constexpr std::array<const char*, 6> RR_PRESET_HINTS = {
    NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_DLAA,
    NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Quality,
    NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Balanced,
    NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_Performance,
    NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_UltraPerformance,
    NVSDK_NGX_Parameter_RayReconstruction_Hint_Render_Preset_UltraQuality
};

/** Write every hint, including zero: NGX shares the parameter block across feature recreations. */
template <typename SetHint>
bool setRayReconstructionPreset(int preset, SetHint&& setHint) {
    switch (preset) {
        case NVSDK_NGX_RayReconstruction_Hint_Render_Preset_Default:
        case NVSDK_NGX_RayReconstruction_Hint_Render_Preset_D:
        case NVSDK_NGX_RayReconstruction_Hint_Render_Preset_E:
        case NVSDK_NGX_RayReconstruction_Hint_Render_Preset_F:
            break;
        default:
            return false;
    }
    for (const char* hint : RR_PRESET_HINTS) {
        setHint(hint, static_cast<unsigned int>(preset));
    }
    return true;
}

} // namespace caustica
