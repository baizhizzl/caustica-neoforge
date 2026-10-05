package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.gen.LightCandidateData;
import dev.comfyfluffy.caustica.rt.gen.LightPresamplePushData;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;

/** One fixed-size, device-local proposal pool, refreshed on the graphics queue before every consuming trace. */
public final class RtLightPresampling {
    private static final String SHADER = "/caustica/shaders/pipelines/light_presample/main.comp.spv";
    private final RtContext ctx;
    private final long layout;
    private final long pipeline;
    private final RtBuffer pool;
    private boolean destroyed;

    private RtLightPresampling(RtContext ctx, long layout, long pipeline, RtBuffer pool) {
        this.ctx = ctx;
        this.layout = layout;
        this.pipeline = pipeline;
        this.pool = pool;
    }

    public static RtLightPresampling create(RtContext ctx) {
        long layout = 0L;
        long pipeline = 0L;
        long module = 0L;
        RtBuffer pool = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(LightPresamplePushData.BYTE_SIZE);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pPushConstantRanges(range);
            LongBuffer handle = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(ctx.vk(), layoutInfo, null, handle), "vkCreatePipelineLayout(light proposals)");
            layout = handle.get(0);
            module = loadModule(ctx, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                    .stage(stage).layout(layout);
            check(VK10.vkCreateComputePipelines(ctx.vk(), VK10.VK_NULL_HANDLE, info, null, handle),
                    "vkCreateComputePipelines(light proposals)");
            pipeline = handle.get(0);
            pool = ctx.createBuffer((long) RtLightSamplePool.MAX_ENTRIES * LightCandidateData.BYTE_SIZE,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, "shared light proposals");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "light proposal prepass");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, "light proposal layout");
            return new RtLightPresampling(ctx, layout, pipeline, pool);
        } catch (RuntimeException | Error failure) {
            if (pool != null) pool.destroy();
            if (pipeline != 0L) VK10.vkDestroyPipeline(ctx.vk(), pipeline, null);
            if (layout != 0L) VK10.vkDestroyPipelineLayout(ctx.vk(), layout, null);
            throw failure;
        } finally {
            if (module != 0L) VK10.vkDestroyShaderModule(ctx.vk(), module, null);
        }
    }

    public long address() {
        return pool.deviceAddress;
    }

    public void record(VkCommandBuffer cmd, long worldPushAddr, long lights, long globalAlias,
                       long localAlias, long cells, long spans, int frameIndex, int entryCount) {
        if (entryCount <= 0 || entryCount > RtLightSamplePool.MAX_ENTRIES) {
            throw new IllegalArgumentException("invalid light-pool entry count " + entryCount);
        }
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "light proposal prepass")) {
            // Prior frames may still read this shared BDA buffer. Also order a prior prepass with no readers.
            barrier(cmd, stack, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR | VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT);
            ByteBuffer push = stack.malloc(LightPresamplePushData.BYTE_SIZE);
            new LightPresamplePushData(worldPushAddr, lights, globalAlias, localAlias, cells, spans,
                    pool.deviceAddress, frameIndex, entryCount).write(push);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdPushConstants(cmd, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (entryCount + 63) / 64, 1, 1);
            barrier(cmd, stack, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK10.VK_ACCESS_SHADER_READ_BIT);
            RtFrameStats.FRAME.count("lightPresampledCandidates", entryCount);
        }
    }

    private static void barrier(VkCommandBuffer cmd, MemoryStack stack, long sourceStage, long sourceAccess,
                                long targetStage, long targetAccess) {
        VkMemoryBarrier2.Buffer memory = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(sourceStage).srcAccessMask(sourceAccess).dstStageMask(targetStage).dstAccessMask(targetAccess);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd,
                VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(memory));
    }

    /** Teardown-only: the renderer guarantees that the device is idle. */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        pool.destroy();
        VK10.vkDestroyPipeline(ctx.vk(), pipeline, null);
        VK10.vkDestroyPipelineLayout(ctx.vk(), layout, null);
    }

    private static long loadModule(RtContext ctx, MemoryStack stack) {
        byte[] bytes;
        try (InputStream input = RtLightPresampling.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing SPIR-V resource " + SHADER);
            bytes = input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + SHADER, failure);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            LongBuffer handle = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(ctx.vk(), VkShaderModuleCreateInfo.calloc(stack).sType$Default()
                    .pCode(code), null, handle), "vkCreateShaderModule(light proposals)");
            return handle.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
