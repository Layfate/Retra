#include "slang_compiler.h"

#include <glslang/Public/ResourceLimits.h>
#include <glslang/Public/ShaderLang.h>
#include <SPIRV/GlslangToSpv.h>
#include <spirv_glsl.hpp>

#include <memory>
#include <mutex>
#include <vector>

namespace retra {
namespace {

std::once_flag glslangInit;

bool toSpirv(EShLanguage stage, const std::string &source, std::vector<uint32_t> &spirv, std::string &error) {
    glslang::TShader shader(stage);
    const char *text = source.c_str();
    shader.setStrings(&text, 1);
    shader.setEnvInput(glslang::EShSourceGlsl, stage, glslang::EShClientVulkan, 100);
    shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_0);
    shader.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_0);
    const auto messages = static_cast<EShMessages>(EShMsgSpvRules | EShMsgVulkanRules);
    const char *name = stage == EShLangVertex ? "vertex" : "fragment";
    if (!shader.parse(GetDefaultResources(), 450, false, messages)) {
        error = std::string(name) + " stage: " + shader.getInfoLog();
        return false;
    }
    glslang::TProgram program;
    program.addShader(&shader);
    if (!program.link(messages)) {
        error = std::string(name) + " stage link: " + program.getInfoLog();
        return false;
    }
    glslang::SpvOptions options;
    options.generateDebugInfo = false;  // OpName survives without it; names are all we need.
    glslang::GlslangToSpv(*program.getIntermediate(stage), spirv, &options);
    return true;
}

std::string toGles(std::vector<uint32_t> spirv, bool vertexStage, uint32_t version) {
    const bool es31 = version >= 310;
    spirv_cross::CompilerGLSL compiler(std::move(spirv));
    spirv_cross::CompilerGLSL::Options options;
    options.version = version;
    options.es = true;
    options.vulkan_semantics = false;
    options.enable_420pack_extension = false;
    options.emit_uniform_buffer_as_plain_uniforms = true;
    // float x[2][3] needs ESSL 3.10; flattening keeps most such shaders on ES 3.00.
    options.flatten_multidimensional_arrays = !es31;
    options.fragment.default_float_precision = spirv_cross::CompilerGLSL::Options::Highp;
    options.fragment.default_int_precision = spirv_cross::CompilerGLSL::Options::Highp;
    compiler.set_common_options(options);

    // GLSL ES 3.00 matches varyings by name; Vulkan matched them by location.
    const auto resources = compiler.get_shader_resources();
    const auto &varyings = vertexStage ? resources.stage_outputs : resources.stage_inputs;
    for (const auto &varying : varyings) {
        const uint32_t location = compiler.get_decoration(varying.id, spv::DecorationLocation);
        compiler.set_name(varying.id, "RETRA_VARYING_" + std::to_string(location));
        // noperspective needs an NVIDIA-only GLES extension; on Retra's flat w = 1 quad,
        // perspective-correct interpolation is identical.
        compiler.unset_decoration(varying.id, spv::DecorationNoPerspective);
    }
    // Uniform blocks without an instance name would otherwise get generated names like "_19".
    for (const auto &block : resources.push_constant_buffers) {
        if (compiler.get_name(block.id).empty()) compiler.set_name(block.id, "RETRA_PUSH");
    }
    for (const auto &block : resources.uniform_buffers) {
        if (compiler.get_name(block.id).empty()) compiler.set_name(block.id, "RETRA_UBO");
    }
    return compiler.compile();
}

}  // namespace

SlangConversion convertSlang(const std::string &vertex450, const std::string &fragment450) {
    std::call_once(glslangInit, [] { glslang::InitializeProcess(); });
    SlangConversion result;
    std::vector<uint32_t> vertexSpirv;
    std::vector<uint32_t> fragmentSpirv;
    if (!toSpirv(EShLangVertex, vertex450, vertexSpirv, result.error)) return result;
    if (!toSpirv(EShLangFragment, fragment450, fragmentSpirv, result.error)) return result;
    // GLSL ES 3.00 first; shaders using ES 3.10 features (textureGather, ...) retry as 3.10,
    // which the OpenGL ES 3.1+ contexts of current Android devices accept.
    for (const uint32_t version : {300u, 310u}) {
        try {
            result.vertex = toGles(vertexSpirv, true, version);
            result.fragment = toGles(fragmentSpirv, false, version);
            result.ok = true;
            result.error.clear();
            return result;
        } catch (const std::exception &error) {
            result.error = std::string("GLSL ES conversion: ") + error.what();
        }
    }
    return result;
}

}  // namespace retra
