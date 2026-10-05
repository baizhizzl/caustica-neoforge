package dev.comfyfluffy.caustica.rt.pipeline;

/** Bounded proposal-pool addressing, independent of GPU or game state. */
public final class RtLightSamplePool {
    public static final int GLOBAL_SAMPLES = 1024;
    public static final int LOCAL_SAMPLES = 64;
    public static final int MAX_CELL_DIM = 8;
    public static final int MAX_ENTRIES = GLOBAL_SAMPLES + MAX_CELL_DIM * MAX_CELL_DIM * MAX_CELL_DIM * LOCAL_SAMPLES;
    public static final Plan DISABLED = new Plan(false, 0, 0, 0, 0, 0, 0);

    private RtLightSamplePool() {}

    public record Plan(boolean active, int originX, int originY, int originZ, int dimX, int dimY, int dimZ) {
        public int entryCount() {
            return active ? GLOBAL_SAMPLES + dimX * dimY * dimZ * LOCAL_SAMPLES : 0;
        }

        public int cellOffset(int x, int y, int z) {
            x -= originX;
            y -= originY;
            z -= originZ;
            if (x < 0 || y < 0 || z < 0 || x >= dimX || y >= dimY || z >= dimZ) return -1;
            return GLOBAL_SAMPLES + ((z * dimY + y) * dimX + x) * LOCAL_SAMPLES;
        }
    }

    public static Plan plan(boolean enabled, int lightCount, int candidates, int gridX, int gridY, int gridZ,
                            double cameraX, double cameraY, double cameraZ,
                            double gridOriginX, double gridOriginY, double gridOriginZ, double cellSize) {
        if (!enabled || lightCount <= 0 || candidates <= 0) return DISABLED;
        if (candidates == 1 || gridX <= 0 || gridY <= 0 || gridZ <= 0 || !Double.isFinite(cellSize) || cellSize <= 0.0
                || !Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)
                || !Double.isFinite(gridOriginX) || !Double.isFinite(gridOriginY) || !Double.isFinite(gridOriginZ)) {
            return new Plan(true, 0, 0, 0, 0, 0, 0);
        }
        int dx = Math.min(MAX_CELL_DIM, gridX);
        int dy = Math.min(MAX_CELL_DIM, gridY);
        int dz = Math.min(MAX_CELL_DIM, gridZ);
        return new Plan(true,
                start(cameraX, gridOriginX, cellSize, gridX, dx),
                start(cameraY, gridOriginY, cellSize, gridY, dy),
                start(cameraZ, gridOriginZ, cellSize, gridZ, dz), dx, dy, dz);
    }

    private static int start(double camera, double gridOrigin, double size, int gridDim, int poolDim) {
        double first = Math.floor((camera - gridOrigin) / size) - poolDim / 2;
        return (int) Math.clamp(first, 0.0, gridDim - poolDim);
    }
}
