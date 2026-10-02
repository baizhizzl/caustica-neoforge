#include "rr_model.h"

#include <iostream>
#include <map>
#include <string>

int main() {
    std::map<std::string, unsigned int> hints;
    int writes = 0;
    auto setHint = [&](const char* key, unsigned int preset) {
        hints[key] = preset;
        ++writes;
    };
    for (int preset : {4, 5, 6, 0, 6, 0}) {
        writes = 0;
        if (!caustica::setRayReconstructionPreset(preset, setHint) || writes != 6 || hints.size() != 6) {
            std::cerr << "Each model selection must overwrite all six RR hints\n";
            return 1;
        }
        for (const auto& hint : hints) {
            if (hint.second != static_cast<unsigned int>(preset)) {
                std::cerr << "Stale RR model hint after selecting " << preset << '\n';
                return 1;
            }
        }
    }
    for (int invalid : {-1, 1, 2, 3, 7, 12, 13, 2147483647}) {
        writes = 0;
        if (caustica::setRayReconstructionPreset(invalid, setHint) || writes != 0) {
            std::cerr << "Unsupported RR/SR preset must not modify the parameter block\n";
            return 1;
        }
    }
    std::cout << "RR preset F/Auto reset and unsupported preset checks passed\n";
    return 0;
}
