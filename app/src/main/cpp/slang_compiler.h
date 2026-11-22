#pragma once

#include <string>

namespace retra {

struct SlangConversion {
    bool ok = false;
    std::string vertex;
    std::string fragment;
    std::string error;
};

/**
 * Compiles a RetroArch .slang pass (Vulkan GLSL 4.50, one source per stage, includes already
 * inlined) to SPIR-V with glslang and emits GLSL ES 3.00 with SPIRV-Cross.
 *
 * Uniform blocks (push constants and UBOs) become uniform structs ("params.SourceSize"),
 * varyings are renamed by location so both stages match, and vertex inputs keep their locations.
 */
SlangConversion convertSlang(const std::string &vertex450, const std::string &fragment450);

}  // namespace retra
