package dev.comfyfluffy.caustica.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;

import static dev.comfyfluffy.caustica.rt.RtContext.check;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.*;

/**
 * Compute pass that composites the vanilla UI overlay (premultiplied sRGB rgba8, sampled) over the
 * PQ-encoded HDR display image in place (decode-blend-reencode, since PQ is nonlinear), at paper white. Used
 * by ordered GUI layers and the final HDR present path before the image is blitted to the PQ swapchain.
 */
public final class RtHdrCompositePipeline {
    private static final String SHADER_DIR = "/caustica/shaders/pipelines/hdr_composite/";
    private static final int PUSH_BYTES = Float.BYTES + Integer.BYTES;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long[] descriptorSets;
    private final long pipelineLayout;
    private final long pipeline;
    private final long[][] boundImages = new long[2][5];
    private boolean destroyed;

    private RtHdrCompositePipeline(RtContext ctx, long dsl, long pool, long[] sets, long layout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSets = sets;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
    }

    public static RtHdrCompositePipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(HDR_COMPOSITE_BINDING_COUNT, stack);
            binds.get(HDR_COMPOSITE_OUTPUT).binding(HDR_COMPOSITE_OUTPUT).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(HDR_COMPOSITE_SOURCE).binding(HDR_COMPOSITE_SOURCE).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(HDR_COMPOSITE_UI_OUTPUT).binding(HDR_COMPOSITE_UI_OUTPUT).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(HDR_COMPOSITE_COVERAGE).binding(HDR_COMPOSITE_COVERAGE)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(hdr ui composite)");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, "hdr ui composite descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4);
            poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(2).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(hdr ui composite)");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, "hdr ui composite descriptor pool");

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl, dsl));
            LongBuffer pSet = stack.mallocLong(2);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(hdr ui composite)");
            long[] sets = {pSet.get(0), pSet.get(1)};
            for (int i = 0; i < sets.length; i++) {
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, sets[i], "hdr ui composite descriptor set " + i);
            }

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(hdr ui composite)");
            long layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, "hdr ui composite pipeline layout");

            long module = loadModule(vk, stack, "main.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, "hdr ui composite shader module");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                    "vkCreateComputePipelines(hdr ui composite)");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pPipeline.get(0), "hdr ui composite compute pipeline");
            VK10.vkDestroyShaderModule(vk, module, null);

            return new RtHdrCompositePipeline(ctx, dsl, pool, sets, layout, pPipeline.get(0));
        }
    }

    /** Bind the in-place HDR image (storage) and the overlay (combined image sampler, GENERAL layout). */
    public void setImages(int slot, long hdrImageView, long sourceView, long uiOutputView, long coverageView, long sampler) {
        long[] bound = boundImages[slot];
        if (bound[0] == hdrImageView && bound[1] == sourceView && bound[2] == uiOutputView && bound[3] == coverageView && bound[4] == sampler) {
            return;
        }
        // Layers and final FG resolution have different immutable sets. Rebinding at a resource epoch
        // waits for previous submissions; no in-flight command buffer ever observes changed descriptors.
        if (bound[0] != 0L) ctx.waitIdle();
        long descriptorSet = descriptorSets[slot];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer hdrInfo = VkDescriptorImageInfo.calloc(1, stack);
            hdrInfo.get(0).imageView(hdrImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer overlayInfo = VkDescriptorImageInfo.calloc(1, stack);
            overlayInfo.get(0).imageView(sourceView).sampler(sampler).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer uiOutputInfo = VkDescriptorImageInfo.calloc(1, stack);
            uiOutputInfo.get(0).imageView(uiOutputView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

            VkDescriptorImageInfo.Buffer coverageInfo = VkDescriptorImageInfo.calloc(1, stack);
            coverageInfo.get(0).imageView(coverageView).sampler(sampler).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(HDR_COMPOSITE_BINDING_COUNT, stack);
            writes.get(HDR_COMPOSITE_OUTPUT).sType$Default().dstSet(descriptorSet).dstBinding(HDR_COMPOSITE_OUTPUT)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(hdrInfo);
            writes.get(HDR_COMPOSITE_SOURCE).sType$Default().dstSet(descriptorSet).dstBinding(HDR_COMPOSITE_SOURCE)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(overlayInfo);
            writes.get(HDR_COMPOSITE_UI_OUTPUT).sType$Default().dstSet(descriptorSet).dstBinding(HDR_COMPOSITE_UI_OUTPUT)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(uiOutputInfo);
            writes.get(HDR_COMPOSITE_COVERAGE).sType$Default().dstSet(descriptorSet).dstBinding(HDR_COMPOSITE_COVERAGE)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(coverageInfo);
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        bound[0] = hdrImageView;
        bound[1] = sourceView;
        bound[2] = uiOutputView;
        bound[3] = coverageView;
        bound[4] = sampler;
    }

    public void dispatch(VkCommandBuffer cmd, int slot, int width, int height, float uiNits, int mode) {
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "hdr ui composite")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSets[slot]), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putFloat(0, uiNits);
            push.putInt(Float.BYTES, mode);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtHdrCompositePipeline.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
