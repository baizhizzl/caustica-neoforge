package dev.comfyfluffy.caustica.rt.light;

import com.mojang.math.Transformation;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.DynamicLight;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float4;
import dev.comfyfluffy.caustica.rt.light.RtDynamicLightSpecs.Candidate;
import dev.comfyfluffy.caustica.rt.light.RtDynamicLightSpecs.EmitterProfile;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import net.minecraft.world.entity.animal.squid.GlowSquid;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

/**
 * Per-frame client collector for dynamic ray-traced spherical area lights: local and remote handheld
 * items, dropped luminous items, glowing/burning mobs, projectiles, falling/display blocks, and vehicles.
 *
 * <p>{@link #collectFrame} must run before {@code RtEntities.beginFrame}: it assigns this frame's
 * per-entity dynamic-shadow exclusion bits and active-emitter flags that entity TLAS masks and
 * {@code EntityGeom.reserved.x} consume in the same frame.
 */
public final class RtDynamicLights {
    /** Bit 0 in an instance mask: secondary rays (shadows, GI, reflections). */
    public static final int MASK_SECONDARY = 0x01;
    /** Number of independent hardware dynamic-shadow groups available to emitter-owning entities. */
    public static final int SHADOW_GROUP_BITS = RtDynamicLightSpecs.SHADOW_GROUP_BITS;

    private static final DynamicLight[] EMPTY = new DynamicLight[0];
    private static final long SLOT_ENTITY_CORE = 0L;
    private static final long SLOT_MAIN_HAND = 1L;
    private static final long SLOT_OFF_HAND = 2L;
    private static final long SLOT_HEAD = 3L;
    private static final double TELEPORT_SNAP_DIST_SQ = 16.0;

    public static final RtDynamicLights INSTANCE = new RtDynamicLights();

    private record CachedItemLight(EmitterProfile profile) {
    }

    private record EntityLightInfo(String path, EmitterProfile intrinsic) {
    }

    private final Long2ObjectOpenHashMap<SmoothedAnchor> smoothedAnchors = new Long2ObjectOpenHashMap<>(32);
    private final List<Candidate> scratchCandidates = new ArrayList<>(32);
    private final Object2ObjectOpenHashMap<Item, CachedItemLight> itemLightCache = new Object2ObjectOpenHashMap<>();
    private final Object2IntOpenHashMap<Item> defaultItemLightLevels = new Object2IntOpenHashMap<>();
    private final Object2ObjectOpenHashMap<EntityType<?>, EntityLightInfo> entityLightCache =
            new Object2ObjectOpenHashMap<>();
    private final LongOpenHashSet activeEmitterEntityIds = new LongOpenHashSet();
    private final Long2IntOpenHashMap emitterExcludeBits = new Long2IntOpenHashMap();
    // Cross-frame hardware shadow-group ownership: slot i owns mask bit 0x04 << i. Keeping an owner in
    // the same slot across frames keeps its instance mask bit-for-bit stable, so the TLAS can refit
    // instead of doing a full rebuild when two nearby emitters swap priority ranks.
    private final int[] activeSlotOwners = new int[SHADOW_GROUP_BITS];
    private final int[] topOwnersScratch = new int[SHADOW_GROUP_BITS];
    private final float[] acesScratch = new float[3];
    private final double[] anchorScratch = new double[3];
    private final double[] handScratch = new double[3];
    private final BlockPos.MutableBlockPos clipBlockPos = new BlockPos.MutableBlockPos();
    private final DynamicLight[][] resultBuffers = new DynamicLight[WorldPushData.DYNAMIC_LIGHTS_CAPACITY + 1][];
    // Double accumulation keeps flicker phase continuous for days of play without a modulo discontinuity.
    private double animSeconds;
    private long lastNanoTime = 0L;

    private static final class SmoothedAnchor {
        double x;
        double y;
        double z;
        long lastSeenFrame;
    }

    private RtDynamicLights() {
        for (int slot = 0; slot < activeSlotOwners.length; slot++) {
            activeSlotOwners[slot] = RtDynamicLightSpecs.NO_ENTITY;
        }
    }

    public void reset() {
        smoothedAnchors.clear();
        scratchCandidates.clear();
        itemLightCache.clear();
        defaultItemLightLevels.clear();
        entityLightCache.clear();
        activeEmitterEntityIds.clear();
        emitterExcludeBits.clear();
        for (int slot = 0; slot < activeSlotOwners.length; slot++) {
            activeSlotOwners[slot] = RtDynamicLightSpecs.NO_ENTITY;
        }
        animSeconds = 0.0;
        lastNanoTime = 0L;
    }

    /** Whether {@code entityId} hosts one of this frame's selected dynamic lights (double-light guard). */
    public boolean isActiveEmitterEntity(int entityId) {
        return activeEmitterEntityIds.contains(entityId);
    }

    /**
     * Hardware shadow-exclusion bit assigned to an emitter-owning entity, or 0 when none was assigned.
     * Selected emitters beyond the first {@link #SHADOW_GROUP_BITS} distinct owners use 0.
     */
    public int excludeBitForEntity(int entityId) {
        return emitterExcludeBits.getOrDefault(entityId, 0);
    }

    /**
     * Resolve the block state of a {@link BlockItem} including its {@code BLOCK_STATE} data component.
     * Returns {@code null} for non-block items. Applying the component keeps a candle with
     * {@code lit=true}, a copper bulb with {@code lit=true}, or a light block with {@code level=0}
     * classified by its actual authored state instead of the item's default state.
     */
    public static BlockState resolveItemBlockState(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        BlockItemStateProperties properties =
                stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY);
        return properties.apply(blockItem.getBlock().defaultBlockState());
    }

    /** Resolve the emitting block state of an {@link ItemStack} when backed by a luminous {@link BlockItem}. */
    public static BlockState resolveEmissiveBlockState(ItemStack stack) {
        BlockState state = resolveItemBlockState(stack);
        return state != null && state.getLightEmission() > 0 ? state : null;
    }

    /** Resolve the vanilla-equivalent light level [0..15] of an {@link ItemStack} for mesh self-emission. */
    public static int resolveItemLightLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        Item item = stack.getItem();
        // The BLOCK_STATE component is state-specific and rare; everything else resolves from the item's
        // default block state and is cached so submitItem never repeats registry-path string matching.
        if (!stack.has(DataComponents.BLOCK_STATE)) {
            if (INSTANCE.defaultItemLightLevels.containsKey(item)) {
                return INSTANCE.defaultItemLightLevels.getInt(item);
            }
            int level = resolveDefaultItemLightLevel(item);
            INSTANCE.defaultItemLightLevels.put(item, level);
            return level;
        }
        EmitterProfile profile = classifyStack(stack, false);
        return profile != null ? profile.lightLevel() : 0;
    }

    /**
     * Classify an item's default state once. The per-frame held/dropped path then never repeats the
     * registry-path string matching that {@link RtDynamicLightSpecs#classifyItem} performs.
     */
    private static int resolveDefaultItemLightLevel(Item item) {
        if (item instanceof BlockItem blockItem) {
            int blockEmission = blockItem.getBlock().defaultBlockState().getLightEmission();
            if (blockEmission > 0) {
                return Math.clamp(blockEmission, 1, 15);
            }
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(item);
        EmitterProfile profile = key != null
                ? RtDynamicLightSpecs.classifyItem(key.getPath(), 0)
                : null;
        return profile != null ? profile.lightLevel() : 0;
    }

    /**
     * Classify a held or dropped stack. Photometry is identical for both contexts, so {@code handheld}
     * only documents the call site and does not alter the profile.
     */
    public static EmitterProfile classifyStack(ItemStack stack, boolean handheld) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return INSTANCE.classifyStackCached(stack);
    }

    private EmitterProfile classifyStackCached(ItemStack stack) {
        Item item = stack.getItem();
        boolean hasBlockState = item instanceof BlockItem && stack.has(DataComponents.BLOCK_STATE);
        if (hasBlockState) {
            BlockState state = resolveItemBlockState(stack);
            Identifier key = BuiltInRegistries.ITEM.getKey(item);
            return key != null
                    ? RtDynamicLightSpecs.classifyItem(key.getPath(), state != null ? state.getLightEmission() : 0)
                    : null;
        }
        CachedItemLight cached = itemLightCache.get(item);
        if (cached == null) {
            int blockEmission = item instanceof BlockItem blockItem
                    ? blockItem.getBlock().defaultBlockState().getLightEmission() : 0;
            Identifier key = BuiltInRegistries.ITEM.getKey(item);
            cached = new CachedItemLight(key != null
                    ? RtDynamicLightSpecs.classifyItem(key.getPath(), blockEmission) : null);
            itemLightCache.put(item, cached);
        }
        return cached.profile();
    }

    /**
     * Gather, smooth, and pack this frame's active dynamic lights in rebased world coordinates.
     */
    public DynamicLight[] collectFrame(RtTerrain terrain, double camX, double camY, double camZ, long frameSerial) {
        activeEmitterEntityIds.clear();
        emitterExcludeBits.clear();
        if (!CausticaConfig.Rt.DynamicLights.ENABLED.value() || terrain == null) {
            return EMPTY;
        }
        float userIntensity = CausticaConfig.Rt.DynamicLights.INTENSITY.value();
        if (userIntensity <= 0.0f) {
            return EMPTY;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return EMPTY;
        }

        long nowNanos = System.nanoTime();
        float dtSeconds = lastNanoTime == 0L
                ? (1.0f / 60.0f)
                : Math.clamp((nowNanos - lastNanoTime) * 1.0e-9f, 0.0f, 0.25f);
        lastNanoTime = nowNanos;
        boolean paused = mc.isPaused() || !level.tickRateManager().runsNormally();
        if (!paused) {
            animSeconds += dtSeconds;
        }

        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float maxDist = CausticaConfig.Rt.DynamicLights.MAX_DISTANCE.value();
        // A surface just inside maxDist can still be lit by a source a little beyond it, so screen the
        // broad phase with generous slack; priority ranking plus the Top-16 cut still bound the real cost.
        double cullDistSq = (double) (maxDist + 16.0f) * (maxDist + 16.0f);
        float smoothingTau = CausticaConfig.Rt.DynamicLights.SMOOTHING.value();
        float alpha = RtDynamicLightSpecs.smoothingAlpha(dtSeconds, smoothingTau);
        boolean allowHeld = CausticaConfig.Rt.DynamicLights.HELD_ITEMS.value();
        boolean allowEntities = CausticaConfig.Rt.DynamicLights.ENTITIES.value();
        boolean allowFlicker = CausticaConfig.Rt.DynamicLights.FLICKER.value();
        float baselineCdM2 = RtMaterialRegistry.defaultEmissionLuminanceCdM2();

        scratchCandidates.clear();
        Entity cameraEntity = mc.getCameraEntity();
        int cameraEntityId = cameraEntity != null ? cameraEntity.getId() : Integer.MIN_VALUE;
        Camera mainCamera = mc.gameRenderer.mainCamera();
        float camXRot = mainCamera != null ? mainCamera.xRot() : 0.0f;
        float camYRot = mainCamera != null ? mainCamera.yRot() : 0.0f;

        // 1. Local camera owner's handheld and head-worn lights (active in both first-person and third-person).
        if (allowHeld && cameraEntity instanceof LivingEntity localLiving && !localLiving.isSpectator()) {
            collectLivingEquipment(level, localLiving, partial, camX, camY, camZ, true,
                    alpha, frameSerial, baselineCdM2, userIntensity, allowFlicker);
        }

        // 2. World entities: remote players/mobs holding lights, dropped items, burning/glowing mobs, projectiles.
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isRemoved() || entity.isSpectator()) {
                continue;
            }
            int entityId = entity.getId();
            // Invisible entities are omitted from the TLAS, so keep their shadow ownership unassigned
            // instead of spending scarce hardware shadow-group bits on non-existent geometry.
            int shadowOwnerId = entity.isInvisible() ? RtDynamicLightSpecs.NO_ENTITY : entityId;
            boolean isLocalCamera = entityId == cameraEntityId;
            double posX = entity.getX(partial);
            double posY = entity.getY(partial);
            double posZ = entity.getZ(partial);
            double dx = posX - camX;
            double dy = (posY + entity.getBbHeight() * 0.5) - camY;
            double dz = posZ - camZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (!isLocalCamera && distSq > cullDistSq) {
                continue;
            }
            if (entity instanceof Display display && !display.shouldRenderAtSqrDistance(distSq)) {
                continue;
            }

            if (!isLocalCamera && allowHeld && entity instanceof LivingEntity living && !living.isInvisible()) {
                collectLivingEquipment(level, living, partial, camX, camY, camZ, false,
                        alpha, frameSerial, baselineCdM2, userIntensity, allowFlicker);
            }

            if (!allowEntities) {
                continue;
            }

            if (entity instanceof ItemEntity itemEntity) {
                EmitterProfile itemProfile = classifyStack(itemEntity.getItem(), false);
                if (itemProfile != null) {
                    long key = packKey(entityId, SLOT_ENTITY_CORE);
                    smoothAnchor(key, posX, posY + 0.28, posZ, alpha, frameSerial);
                    addCandidate(anchorScratch[0], anchorScratch[1], anchorScratch[2], camX, camY, camZ,
                            itemProfile, key, shadowOwnerId, animSeconds, baselineCdM2, userIntensity,
                            allowFlicker, 1.0f);
                    continue;
                }
                // A non-emissive dropped stack may still be burning; fall through to the fire path.
            }

            if (entity instanceof ItemFrame frame) {
                EmitterProfile frameProfile = classifyStack(frame.getItem(), false);
                if (frameProfile == null && frame instanceof GlowItemFrame) {
                    frameProfile = RtDynamicLightSpecs.glowItemFrameProfile();
                }
                if (frameProfile != null) {
                    long key = packKey(entityId, SLOT_ENTITY_CORE);
                    Direction dir = frame.getDirection();
                    RtDynamicLightSpecs.itemFrameOffset(dir.getStepX(), dir.getStepY(), dir.getStepZ(), handScratch);
                    smoothAnchor(key, posX + handScratch[0], posY + handScratch[1], posZ + handScratch[2],
                            alpha, frameSerial);
                    addCandidate(anchorScratch[0], anchorScratch[1], anchorScratch[2], camX, camY, camZ,
                            frameProfile, key, shadowOwnerId, animSeconds, baselineCdM2, userIntensity,
                            allowFlicker, 1.0f);
                    continue;
                }
            }

            if (entity instanceof Display.ItemDisplay itemDisplay) {
                Display.ItemDisplay.ItemRenderState renderState = itemDisplay.itemRenderState();
                EmitterProfile displayProfile = renderState != null
                        ? classifyStack(renderState.itemStack(), false) : null;
                if (displayProfile != null) {
                    long key = packKey(entityId, SLOT_ENTITY_CORE);
                    writeDisplayOffset(itemDisplay, partial, camXRot, camYRot, 0.0f, 0.0f, 0.0f);
                    smoothAnchor(key, posX + handScratch[0], posY + handScratch[1], posZ + handScratch[2],
                            alpha, frameSerial);
                    addCandidate(anchorScratch[0], anchorScratch[1], anchorScratch[2], camX, camY, camZ,
                            displayProfile, key, shadowOwnerId, animSeconds, baselineCdM2, userIntensity,
                            allowFlicker, 1.0f);
                    continue;
                }
            }

            boolean darkened = isTemporarilyDarkened(entity);
            BlockState containedBlock = resolveContainedBlockState(entity);
            int containedBlockLight = containedBlock != null ? containedBlock.getLightEmission() : 0;
            boolean onFire = entity.isOnFire();
            if (darkened && !onFire && containedBlockLight == 0) {
                continue;
            }
            EntityLightInfo info = entityLightInfo(entity);
            EmitterProfile entityProfile = darkened ? null : info.intrinsic();
            if (entityProfile == null && (onFire || containedBlockLight > 0)) {
                // A darkened glow squid falls back to its fire/contained-block light, not its intrinsic glow.
                Identifier blockKey = containedBlockLight > 0
                        ? BuiltInRegistries.BLOCK.getKey(containedBlock.getBlock()) : null;
                entityProfile = RtDynamicLightSpecs.classifyEntity(
                        darkened ? null : info.path(), onFire,
                        blockKey != null ? blockKey.getPath() : null, containedBlockLight);
            }
            if (entityProfile != null) {
                long key = packKey(entityId, SLOT_ENTITY_CORE);
                if (entity instanceof Display.BlockDisplay blockDisplay) {
                    writeDisplayOffset(blockDisplay, partial, camXRot, camYRot, 0.5f, 0.5f, 0.5f);
                    smoothAnchor(key, posX + handScratch[0], posY + handScratch[1], posZ + handScratch[2],
                            alpha, frameSerial);
                } else {
                    double centerY = posY + Math.max(entity.getBbHeight() * 0.55, 0.22);
                    smoothAnchor(key, posX, centerY, posZ, alpha, frameSerial);
                }
                float selfPriority = isLocalCamera ? 2.5f : 1.0f;
                addCandidate(anchorScratch[0], anchorScratch[1], anchorScratch[2], camX, camY, camZ,
                        entityProfile, key, shadowOwnerId, animSeconds, baselineCdM2, userIntensity,
                        allowFlicker, selfPriority);
            }
        }

        evictStaleAnchors(frameSerial);

        int candidateCount = scratchCandidates.size();
        if (candidateCount == 0) {
            return EMPTY;
        }
        int selectedCount = Math.min(candidateCount, WorldPushData.DYNAMIC_LIGHTS_CAPACITY);
        // Priority order decides who receives the scarce hardware shadow-group bits.
        RtDynamicLightSpecs.sortCandidatesByPriority(scratchCandidates);
        for (int i = 0; i < selectedCount; i++) {
            int ownerEntityId = scratchCandidates.get(i).ownerEntityId();
            if (ownerEntityId != RtDynamicLightSpecs.NO_ENTITY) {
                activeEmitterEntityIds.add(ownerEntityId);
            }
        }
        RtDynamicLightSpecs.updateShadowGroupOwners(
                scratchCandidates, selectedCount, activeSlotOwners, topOwnersScratch);
        emitterExcludeBits.clear();
        for (int slot = 0; slot < SHADOW_GROUP_BITS; slot++) {
            int owner = activeSlotOwners[slot];
            if (owner != RtDynamicLightSpecs.NO_ENTITY) {
                emitterExcludeBits.put(owner, RtDynamicLightSpecs.shadowGroupBit(slot));
            }
        }
        // Slot order is stable-key order so frame-to-frame slot indices do not permute.
        RtDynamicLightSpecs.sortCandidatesByStableKey(scratchCandidates, selectedCount);

        DynamicLight[] result = resultBuffers[selectedCount];
        if (result == null) {
            result = new DynamicLight[selectedCount];
            resultBuffers[selectedCount] = result;
        }
        for (int i = 0; i < selectedCount; i++) {
            Candidate candidate = scratchCandidates.get(i);
            int excludeBit = candidate.ownerEntityId() != RtDynamicLightSpecs.NO_ENTITY
                    ? emitterExcludeBits.getOrDefault(candidate.ownerEntityId(), 0) : 0;
            int shadowCullMask = excludeBit != 0 ? excludeBit : MASK_SECONDARY;
            result[i] = new DynamicLight(
                    new Float4(
                            (float) (candidate.worldX() - terrain.blockX),
                            (float) (candidate.worldY() - terrain.blockY),
                            (float) (candidate.worldZ() - terrain.blockZ),
                            candidate.radius()),
                    new Float4(
                            candidate.acesR(),
                            candidate.acesG(),
                            candidate.acesB(),
                            (float) shadowCullMask));
        }
        return result;
    }

    private EntityLightInfo entityLightInfo(Entity entity) {
        EntityLightInfo info = entityLightCache.get(entity.getType());
        if (info == null) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            String path = key != null ? key.getPath() : "";
            info = new EntityLightInfo(path, RtDynamicLightSpecs.classifyEntity(path, false, 0));
            entityLightCache.put(entity.getType(), info);
        }
        return info;
    }

    private void collectLivingEquipment(ClientLevel level, LivingEntity living, float partial,
                                        double camX, double camY, double camZ, boolean isLocalCamera,
                                        float alpha, long frameSerial,
                                        float baselineCdM2, float userIntensity, boolean allowFlicker) {
        ItemStack mainStack = living.getMainHandItem();
        ItemStack offStack = living.getOffhandItem();
        ItemStack headStack = living.getItemBySlot(EquipmentSlot.HEAD);

        EmitterProfile mainProfile = classifyStack(mainStack, true);
        EmitterProfile offProfile = classifyStack(offStack, true);
        EmitterProfile headProfile = classifyStack(headStack, true);
        if (mainProfile == null && offProfile == null && headProfile == null) {
            return;
        }
        // Quadrupeds and other non-humanoids carry luminous items at the mouth/head centre instead of
        // off to the side of a humanoid hand, so classify the mouth-carriers positively.
        boolean mouthAnchored = usesMouthAnchor(living);

        boolean firstPersonLocal = isLocalCamera && Minecraft.getInstance().options.getCameraType().isFirstPerson();
        float yawDeg;
        float pitchDeg;
        if (firstPersonLocal) {
            yawDeg = living.getViewYRot(partial);
            pitchDeg = living.getViewXRot(partial);
        } else {
            // Third-person local player, remote players, and creatures: follow the interpolated body yaw.
            yawDeg = Mth.rotLerp(partial, living.yBodyRotO, living.yBodyRot);
            // A head that looks straight down must not drag the hand light into the feet (or the sky).
            pitchDeg = Mth.clamp(living.getViewXRot(partial), -20.0f, 20.0f);
        }
        boolean bodyAligned = living.isFallFlying() || living.isVisuallySwimming();
        float posePitchDeg = bodyAligned ? 0.0f : pitchDeg;
        float scale = living.getScale();
        float yawRad = yawDeg * Mth.DEG_TO_RAD;
        float pitchRad = posePitchDeg * Mth.DEG_TO_RAD;
        float cosPitch = Mth.cos(pitchRad);
        double forwardX = -Mth.sin(yawRad) * cosPitch;
        double forwardY = -Mth.sin(pitchRad);
        double forwardZ = Mth.cos(yawRad) * cosPitch;

        Vec3 eyePos = living.getEyePosition(partial);
        boolean rightHanded = living.getMainArm() == HumanoidArm.RIGHT;
        double mainSide = mouthAnchored ? 0.0 : (rightHanded ? 1.0 : -1.0);
        float priorityBoost = isLocalCamera ? 4.0f : 1.25f;
        int shadowOwnerId = living.isInvisible() ? RtDynamicLightSpecs.NO_ENTITY : living.getId();

        if (mainProfile != null) {
            writeHandOffset(mouthAnchored, yawDeg, posePitchDeg, mainSide, scale);
            submitLivingSlotCandidate(level, living, eyePos, handScratch[0], handScratch[1], handScratch[2],
                    camX, camY, camZ, mainProfile, SLOT_MAIN_HAND, shadowOwnerId, alpha, frameSerial,
                    baselineCdM2, userIntensity, allowFlicker, priorityBoost);
        }

        if (offProfile != null) {
            writeHandOffset(mouthAnchored, yawDeg, posePitchDeg, -mainSide, scale);
            submitLivingSlotCandidate(level, living, eyePos, handScratch[0], handScratch[1], handScratch[2],
                    camX, camY, camZ, offProfile, SLOT_OFF_HAND, shadowOwnerId, alpha, frameSerial,
                    baselineCdM2, userIntensity, allowFlicker, priorityBoost);
        }

        if (headProfile != null) {
            double reach = 0.28 * scale;
            double rise = 0.18 * scale;
            submitLivingSlotCandidate(level, living, eyePos,
                    forwardX * reach, forwardY * reach + rise, forwardZ * reach,
                    camX, camY, camZ, headProfile, SLOT_HEAD, shadowOwnerId, alpha, frameSerial,
                    baselineCdM2, userIntensity, allowFlicker, priorityBoost);
        }
    }

    /** Smooth the unclipped anchor first, then clip against walls from this frame's eye position. */
    private void submitLivingSlotCandidate(ClientLevel level, LivingEntity living, Vec3 eyePos,
                                           double offsetX, double offsetY, double offsetZ,
                                           double camX, double camY, double camZ,
                                           EmitterProfile profile, long slot, int shadowOwnerId,
                                           float alpha, long frameSerial, float baselineCdM2,
                                           float userIntensity, boolean allowFlicker, float priorityBoost) {
        long key = packKey(living.getId(), slot);
        smoothAnchor(key, eyePos.x + offsetX, eyePos.y + offsetY, eyePos.z + offsetZ, alpha, frameSerial);
        double tx = anchorScratch[0];
        double ty = anchorScratch[1];
        double tz = anchorScratch[2];
        if (!isOpenAirSpan(level, eyePos.x, eyePos.y, eyePos.z, tx, ty, tz)) {
            Vec3 clipped = clipOutOfWalls(level, living, eyePos, new Vec3(tx, ty, tz));
            tx = clipped.x;
            ty = clipped.y;
            tz = clipped.z;
        }
        addCandidate(tx, ty, tz, camX, camY, camZ,
                profile, key, shadowOwnerId, animSeconds, baselineCdM2, userIntensity,
                allowFlicker, priorityBoost);
    }

    private boolean isOpenAirSpan(ClientLevel level, double x0, double y0, double z0,
                                  double x1, double y1, double z1) {
        int minX = Mth.floor(Math.min(x0, x1));
        int maxX = Mth.floor(Math.max(x0, x1));
        int minY = Mth.floor(Math.min(y0, y1));
        int maxY = Mth.floor(Math.max(y0, y1));
        int minZ = Mth.floor(Math.min(z0, z1));
        int maxZ = Mth.floor(Math.max(z0, z1));
        if ((maxX - minX) > 2 || (maxY - minY) > 2 || (maxZ - minZ) > 2) {
            return false;
        }
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    clipBlockPos.set(bx, by, bz);
                    BlockState state = level.getBlockState(clipBlockPos);
                    if (!state.isAir() && !state.getCollisionShape(level, clipBlockPos).isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void writeDisplayOffset(Display display, float partial, float camXRot, float camYRot,
                                    float localX, float localY, float localZ) {
        Display.RenderState renderState = display.renderState();
        float rotYDeg = display.getYRot(partial);
        float rotXDeg = display.getXRot(partial);
        Matrix4fc matrix = null;
        if (renderState != null) {
            switch (renderState.billboardConstraints()) {
                case FIXED -> {
                }
                case HORIZONTAL -> rotXDeg = -camXRot;
                case VERTICAL -> rotYDeg = camYRot - 180.0f;
                case CENTER -> {
                    rotYDeg = camYRot - 180.0f;
                    rotXDeg = -camXRot;
                }
            }
            Transformation transformation = renderState.transformation()
                    .get(display.calculateInterpolationProgress(partial));
            matrix = transformation.getMatrix();
        }
        RtDynamicLightSpecs.displayOffset(matrix, rotYDeg, rotXDeg, localX, localY, localZ, handScratch);
    }

    /**
     * Non-humanoid creatures (foxes, wolves and other animals, dolphins, allays) animate their carried
     * luminous item at the head/mouth, not beside a humanoid torso.
     */
    private static boolean usesMouthAnchor(LivingEntity living) {
        return living instanceof Animal || living instanceof Dolphin || living instanceof Allay;
    }

    /** Write the held-light offset into {@link #handScratch} for the creature's carrying pose. */
    private void writeHandOffset(boolean mouthAnchored, float yawDeg, float pitchDeg,
                                 double sideSign, float scale) {
        if (mouthAnchored) {
            RtDynamicLightSpecs.mouthOffset(yawDeg, pitchDeg, scale, handScratch);
        } else {
            RtDynamicLightSpecs.handOffset(yawDeg, pitchDeg, sideSign, scale, handScratch);
        }
    }

    /**
     * Keep a handheld light out of solid geometry. Collider shapes (not visual shapes) define walls, so
     * grass, webs, torches and vines never pull the light onto the camera; the hit surface normal then
     * backs the light off by a small, hardware-mask-safe margin.
     */
    private static Vec3 clipOutOfWalls(ClientLevel level, Entity owner, Vec3 start, Vec3 target) {
        BlockHitResult hit = level.clip(new ClipContext(
                start, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        if (hit.getType() == HitResult.Type.MISS) {
            return target;
        }
        // Starting inside a non-full-block collider (slab, stairs, trapdoor) makes AABB.clip return the
        // exit face, whose normal would push the light through the wall. Keep the start point instead.
        if (hit.isInside()) {
            return start;
        }
        Vec3 hitLoc = hit.getLocation();
        if (hitLoc.distanceToSqr(start) < 4.0e-4) {
            return start;
        }
        Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i());
        Vec3 pulled = hitLoc.add(normal.scale(0.05));
        return pulled.distanceToSqr(start) < 4.0e-4 ? start : pulled;
    }

    private static boolean isTemporarilyDarkened(Entity entity) {
        return entity instanceof GlowSquid glowSquid && glowSquid.getDarkTicksRemaining() > 0;
    }

    private static BlockState resolveContainedBlockState(Entity entity) {
        if (entity instanceof FallingBlockEntity falling) {
            return falling.getBlockState();
        }
        if (entity instanceof Display.BlockDisplay blockDisplay) {
            Display.BlockDisplay.BlockRenderState renderState = blockDisplay.blockRenderState();
            return renderState != null ? renderState.blockState() : null;
        }
        if (entity instanceof AbstractMinecart minecart) {
            return minecart.getDisplayBlockState();
        }
        return null;
    }

    private void addCandidate(double wx, double wy, double wz,
                              double camX, double camY, double camZ,
                              EmitterProfile profile, long stableKey, int ownerEntityId,
                              double animSeconds, float baselineCdM2, float userIntensity,
                              boolean allowFlicker, float priorityBoost) {
        float flicker = (allowFlicker && profile.flickers())
                ? RtDynamicLightSpecs.flameFlicker(animSeconds, stableKey)
                : 1.0f;
        float luma = RtDynamicLightSpecs.writeAcesCgIntensity(
                profile, baselineCdM2, userIntensity, flicker, acesScratch);
        if (luma <= 1.0e-3f) {
            return;
        }
        double dx = wx - camX;
        double dy = wy - camY;
        double dz = wz - camZ;
        float distSq = (float) (dx * dx + dy * dy + dz * dz);
        float score = (luma * priorityBoost) / (distSq + 2.0f);
        scratchCandidates.add(new Candidate(
                wx, wy, wz, profile.radius(),
                acesScratch[0], acesScratch[1], acesScratch[2], ownerEntityId,
                score, stableKey));
    }

    /** Smooth one anchor in place and expose the filtered position through {@link #anchorScratch}. */
    private void smoothAnchor(long key, double targetX, double targetY, double targetZ,
                              float alpha, long frameSerial) {
        SmoothedAnchor slot = smoothedAnchors.get(key);
        if (slot == null) {
            slot = new SmoothedAnchor();
            slot.x = targetX;
            slot.y = targetY;
            slot.z = targetZ;
            slot.lastSeenFrame = frameSerial;
            smoothedAnchors.put(key, slot);
            anchorScratch[0] = targetX;
            anchorScratch[1] = targetY;
            anchorScratch[2] = targetZ;
            return;
        }
        double dx = targetX - slot.x;
        double dy = targetY - slot.y;
        double dz = targetZ - slot.z;
        boolean discontinuous = (frameSerial - slot.lastSeenFrame > 4L)
                || (dx * dx + dy * dy + dz * dz > TELEPORT_SNAP_DIST_SQ);
        if (discontinuous || alpha >= 1.0f) {
            slot.x = targetX;
            slot.y = targetY;
            slot.z = targetZ;
        } else {
            slot.x += dx * alpha;
            slot.y += dy * alpha;
            slot.z += dz * alpha;
        }
        slot.lastSeenFrame = frameSerial;
        anchorScratch[0] = slot.x;
        anchorScratch[1] = slot.y;
        anchorScratch[2] = slot.z;
    }

    private void evictStaleAnchors(long frameSerial) {
        if (smoothedAnchors.isEmpty()) {
            return;
        }
        var iterator = smoothedAnchors.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (frameSerial - entry.getValue().lastSeenFrame > 30L) {
                iterator.remove();
            }
        }
    }

    private static long packKey(int entityId, long slot) {
        return (Integer.toUnsignedLong(entityId) << 3) | (slot & 7L);
    }
}
