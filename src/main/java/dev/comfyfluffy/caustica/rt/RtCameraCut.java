package dev.comfyfluffy.caustica.rt;

/**
 * Decides when temporal history must restart instead of reprojecting from the previous frame.
 *
 * <p>Motion vectors, DLSS Ray Reconstruction and DLSS Frame Generation all assume the previous frame shows
 * the same scene from a nearby camera. A world change, a first/third-person switch, a teleport or an
 * explicit render-state invalidation breaks that assumption: reprojecting across it drags the old view
 * into the new one for several frames. NVIDIA's integration guides require the reset flag on such cuts.
 *
 * <p>World and camera mode are compared by identity, so callers pass the live level and camera-type
 * objects. Render thread only.
 */
public final class RtCameraCut {
    /**
     * Largest camera movement, in blocks per rendered frame, that is still treated as continuous. Elytra
     * or spectator flight at low frame rates stays well below it; teleports, respawns and long ender-pearl
     * throws exceed it.
     */
    static final double MAX_CONTINUOUS_MOVE = 16.0;

    public enum Kind {
        NONE,
        /** A cut forced by a render-state invalidation. */
        RESTART,
        /** A different level object: joining a world, changing dimension, or the first frame ever. */
        WORLD,
        CAMERA_MODE,
        JUMP;

        public boolean isCut() {
            return this != NONE;
        }
    }

    private Object level;
    private Object cameraMode;
    private double x;
    private double y;
    private double z;
    private boolean valid;
    private boolean forced;

    /** Treat the next frame as a cut, e.g. after F3+A or a render-distance change rebuilt the scene. */
    public void force() {
        forced = true;
    }

    /** Record this frame's camera and classify the transition from the previous recorded frame. */
    public Kind update(Object level, Object cameraMode, double x, double y, double z) {
        Kind kind;
        // A world change is reported even when a forced restart is pending: dimension changes also fire the
        // render-state invalidation, and only a world change resets exposure.
        if (level != this.level) {
            kind = Kind.WORLD;
        } else if (!valid || forced) {
            kind = Kind.RESTART;
        } else if (cameraMode != this.cameraMode) {
            kind = Kind.CAMERA_MODE;
        } else {
            double dx = x - this.x;
            double dy = y - this.y;
            double dz = z - this.z;
            // The negated comparison also classifies a NaN position as a jump rather than continuous motion.
            kind = !(dx * dx + dy * dy + dz * dz <= MAX_CONTINUOUS_MOVE * MAX_CONTINUOUS_MOVE)
                    ? Kind.JUMP : Kind.NONE;
        }
        this.level = level;
        this.cameraMode = cameraMode;
        this.x = x;
        this.y = y;
        this.z = z;
        valid = true;
        forced = false;
        return kind;
    }
}
