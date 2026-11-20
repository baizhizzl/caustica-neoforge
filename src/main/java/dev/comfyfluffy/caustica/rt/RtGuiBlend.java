package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import java.util.Optional;
import java.util.EnumMap;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;

/** Ordered GUI blend layers and the SDR resources needed for DLSS UI recomposition. */
public final class RtGuiBlend {
    private static final BlendEquation VIGNETTE = new BlendEquation(BlendFactor.ZERO,
            BlendFactor.ONE_MINUS_SRC_COLOR, BlendOp.ADD);
    private static final BlendEquation ADD_ALPHA = new BlendEquation(BlendFactor.SRC_ALPHA, BlendFactor.ONE, BlendOp.ADD);
    private static final BindGroupLayout RESOLVE_INPUTS = BindGroupLayout.builder()
            .withUniform("WorldSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("FinalSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("CoverageSampler", UniformType.COMBINED_IMAGE_SAMPLER).build();
    private static final RenderPipeline RESOLVE = RenderPipeline.builder()
            .withLocation("pipeline/caustica_gui_resolve_sdr_ui")
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("caustica", "core/gui_resolve_ui"))
            .withBindGroupLayout(RESOLVE_INPUTS)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    private static final EnumMap<Mode, RenderPipeline> COVERAGE = new EnumMap<>(Mode.class);

    // IDs match gui_blend_math.slang. Each mask encodes a single operation's effect on any background.
    public enum Mode {
        OVER(0, BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA),
        MULTIPLY(1, new BlendFunction(BlendFactor.ZERO, BlendFactor.SRC_COLOR, BlendFactor.ZERO, BlendFactor.ONE)),
        INVERT(2, new BlendFunction(BlendFunction.INVERT.color(),
                new BlendEquation(BlendFactor.ZERO, BlendFactor.ONE, BlendOp.ADD))),
        ADD(3, BlendFunction.ADDITIVE),
        MAX(4, BlendFunction.MAX);

        private final int id;
        private final RenderPipeline composite;

        Mode(int id, BlendFunction blend) {
            this.id = id;
            var builder = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                    .withLocation("pipeline/caustica_gui_blend_" + name().toLowerCase(java.util.Locale.ROOT))
                    .withVertexShader("core/screenquad")
                    .withFragmentShader("core/blit_screen")
                    .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                    .withColorTargetState(new ColorTargetState(Optional.of(blend), GpuFormat.RGBA8_UNORM,
                            ColorTargetState.WRITE_COLOR))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
            composite = builder.build();
        }

        public int id() {
            return id;
        }
    }

    private static TextureTarget sdrHudless;
    private static TextureTarget resolvedSdrUi;
    private static TextureTarget uiCoverage;
    private static boolean segmented;
    private static boolean hudlessReady;
    private static boolean resolvedReady;
    private static boolean coverageStarted;

    private RtGuiBlend() {
    }

    public static Mode mode(RenderPipeline pipeline) {
        var targets = pipeline.getColorTargetStates();
        if (targets.isEmpty() || targets.getFirst() == null) {
            return Mode.OVER;
        }
        BlendFunction blend = targets.getFirst().blendFunction().orElse(null);
        if (blend == null) {
            return Mode.OVER;
        }
        BlendEquation color = blend.color();
        if (color.equals(VIGNETTE)) return Mode.MULTIPLY;
        if (color.equals(BlendFunction.INVERT.color())) return Mode.INVERT;
        if (color.equals(BlendFunction.ADDITIVE.color()) || color.equals(ADD_ALPHA)
                || (color.destFactor() == BlendFactor.ONE && color.sourceFactor() != BlendFactor.ZERO
                    && color.op() == BlendOp.ADD)) return Mode.ADD;
        return color.op() == BlendOp.MAX ? Mode.MAX : Mode.OVER;
    }

    static void beginFrame() {
        segmented = false;
        hudlessReady = false;
        resolvedReady = false;
        coverageStarted = false;
    }

    static boolean segmented() {
        return segmented;
    }

    static void beginSegments(RenderTarget overlay) {
        if (segmented) return;
        segmented = true;
        uiCoverage = sized(uiCoverage, "FG GUI coverage", overlay.width, overlay.height);
        if (!coverageStarted) {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(uiCoverage.getColorTexture(),
                    new org.joml.Vector4f());
            coverageStarted = true;
        }
    }

    static void mergeCoverage(RenderTarget layer, Mode mode) {
        if (uiCoverage == null) return;
        RenderPipeline pipeline = COVERAGE.computeIfAbsent(mode, key -> RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withLocation("pipeline/caustica_gui_coverage_" + key.name().toLowerCase(java.util.Locale.ROOT))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.fromNamespaceAndPath("caustica", "core/gui_coverage"))
                .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                .withShaderDefine(switch (key) {
                    case OVER -> "COVERAGE_OVER";
                    case MULTIPLY -> "COVERAGE_MULTIPLY";
                    case INVERT -> "COVERAGE_INVERT";
                    case ADD -> "COVERAGE_ADD";
                    case MAX -> "COVERAGE_MAX";
                })
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA),
                        GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_RED | ColorTargetState.WRITE_ALPHA))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build());
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(() -> "Accumulate FG UI coverage",
                uiCoverage.getColorTextureView(), java.util.Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            bind(pass, "InSampler", layer.getColorTextureView());
            pass.draw(3, 1, 0, 0);
        }
    }

    static long coverageView() {
        return !coverageStarted || uiCoverage == null ? 0L
                : ((VulkanGpuTextureView) uiCoverage.getColorTextureView()).vkImageView();
    }

    static long coverageViewOr(long fallback) {
        long view = coverageView();
        return view == 0L ? fallback : view;
    }

    static void compositeSdr(RenderTarget main, RenderTarget overlay, Mode mode) {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(() -> "GUI " + mode.name(),
                main.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(mode.composite));
            RenderSystem.bindDefaultUniforms(pass);
            bind(pass, "InSampler", overlay.getColorTextureView());
            pass.draw(3, 1, 0, 0);
        }
    }

    static void captureSdrHudless(RenderTarget main) {
        if (!hudlessReady) {
            sdrHudless = sized(sdrHudless, "FG SDR world without UI", main.width, main.height);
            sdrHudless.copyColorFrom(main);
            hudlessReady = true;
        }
    }

    static void resolveSdrUi(RenderTarget main, RenderTarget overlay) {
        if (!hudlessReady || sdrHudless.width != main.width || sdrHudless.height != main.height) {
            return;
        }
        resolvedSdrUi = sized(resolvedSdrUi, "FG resolved SDR UI", main.width, main.height);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Resolve SDR UI for frame generation", resolvedSdrUi.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(RESOLVE));
            bind(pass, "WorldSampler", sdrHudless.getColorTextureView());
            bind(pass, "FinalSampler", main.getColorTextureView());
            bind(pass, "CoverageSampler", uiCoverage.getColorTextureView());
            pass.draw(3, 1, 0, 0);
        }
        resolvedReady = true;
    }

    static Image sdrHudless(int width, int height) {
        return hudlessReady ? image(sdrHudless, width, height) : null;
    }

    static Image resolvedSdrUi(int width, int height) {
        return resolvedReady ? image(resolvedSdrUi, width, height) : null;
    }

    record Image(long view, long image) {
    }

    static void destroy() {
        if (sdrHudless != null) sdrHudless.destroyBuffers();
        if (uiCoverage != null) uiCoverage.destroyBuffers();
        if (resolvedSdrUi != null) resolvedSdrUi.destroyBuffers();
        sdrHudless = null;
        uiCoverage = null;
        resolvedSdrUi = null;
        beginFrame();
    }

    private static TextureTarget sized(TextureTarget target, String name, int width, int height) {
        if (target == null) {
            return new TextureTarget(name, width, height, GpuFormat.RGBA8_UNORM, null);
        }
        if (target.width != width || target.height != height) {
            target.resize(width, height);
        }
        return target;
    }

    private static void bind(RenderPass pass, String name, GpuTextureView view) {
        pass.setUniform(name, view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
    }

    private static Image image(TextureTarget target, int width, int height) {
        if (target.width != width || target.height != height) {
            return null;
        }
        return new Image(((VulkanGpuTextureView) target.getColorTextureView()).vkImageView(),
                ((VulkanGpuTexture) target.getColorTexture()).vkImage());
    }
}
