package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtHdr;
import dev.comfyfluffy.caustica.rt.VulkanDiagnostics;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Negotiate RT, upscaler and diagnostics features before Renderpearl creates the Vulkan device. */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    @Unique
    private static final List<VulkanFeature> CAUSTICA_SDK_FEATURES = List.of(
            new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderStorageImageWriteWithoutFormat"),
            new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderInt16"),
            new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderFloat16"));
    @Unique
    private static final List<String> CAUSTICA_SDK_EXTENSIONS = List.of(
            "VK_KHR_get_memory_requirements2", "VK_KHR_dedicated_allocation",
            "VK_NVX_binary_import", "VK_NVX_image_view_handle", "VK_KHR_push_descriptor");

    /** NVIDIA's native checkpoints provide more useful fault context than its AMD marker emulation. */
    @WrapOperation(method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;checkCondition(Lorg/lwjgl/vulkan/VkPhysicalDevice;)Z"))
    private boolean caustica$preferNativeCheckpoints(FeatureSet featureSet, VkPhysicalDevice device,
            Operation<Boolean> original) {
        if (caustica$isNvidia(device)) {
            if (featureSet == VulkanFeatureSets.AMD_BUFFER_MARKER_FEATURESET) {
                return false;
            }
            if (featureSet == VulkanFeatureSets.NV_DIAGNOSTIC_CHECKPOINT_FEATURESET) {
                return true;
            }
        }
        return original.call(featureSet, device);
    }

    @ModifyArgs(method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;"))
    private void caustica$augmentFeatureSet(Args args) {
        FeatureSet requested = args.get(0);
        VulkanPhysicalDevice physicalDevice = args.get(1);
        var extensions = new ArrayList<>(requested.extensions());
        Set<VulkanFeature> features = new HashSet<>(requested.features());
        for (String extension : CAUSTICA_SDK_EXTENSIONS) {
            if (!extensions.contains(extension) && physicalDevice.hasDeviceExtension(extension)) {
                extensions.add(extension);
                CausticaMod.LOGGER.info("Enabling device extension {} for Caustica", extension);
            }
        }
        VulkanDiagnostics.addDeviceFaultExtension(extensions, physicalDevice);
        RtHdr.addDeviceExtension(extensions, physicalDevice);
        RtDeviceBringup.addExtensions(extensions, physicalDevice);
        for (VulkanFeature feature : CAUSTICA_SDK_FEATURES) {
            if (caustica$supportsFeature(physicalDevice, feature)) {
                features.add(feature);
            }
        }
        VulkanDiagnostics.addDeviceFaultFeature(features);
        RtDeviceBringup.addFeatures(features, physicalDevice);
        args.set(0, new FeatureSet(requested.name(), new HashSet<>(extensions), features, requested.condition()));
        VulkanDiagnostics.logEnabledExtensions(extensions);
    }

    @Unique
    private static boolean caustica$supportsFeature(VulkanPhysicalDevice physicalDevice, VulkanFeature feature) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 deviceFeatures = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            feature.struct().findOrCreateStructInPNextChain(deviceFeatures, stack);
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), deviceFeatures);
            return feature.get(deviceFeatures);
        }
    }

    @Unique
    private static boolean caustica$isNvidia(VkPhysicalDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(device, properties);
            return properties.vendorID() == 0x10de;
        }
    }

    @Inject(method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;createVma(Lorg/lwjgl/vulkan/VkDevice;)J"))
    private void caustica$probeDevice(GpuDebugOptions options, CallbackInfoReturnable<GpuDevice> cir,
            @Local VkDevice device) {
        VulkanDiagnostics.probe(device);
        RtDeviceBringup.probe(device);
    }

    @Inject(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static void caustica$augmentDeviceCreateInfo(FeatureSet features, VulkanPhysicalDevice physicalDevice,
            CallbackInfoReturnable<VkDevice> cir, @Local VkDeviceCreateInfo deviceCreateInfo) {
        RtDeviceBringup.reserveComputeQueue(deviceCreateInfo, physicalDevice, MemoryStack.stackGet());
        VulkanDiagnostics.attachNvDiagnosticsConfig(deviceCreateInfo, MemoryStack.stackGet());
    }
}
