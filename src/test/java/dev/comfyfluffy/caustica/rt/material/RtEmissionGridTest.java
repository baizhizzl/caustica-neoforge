package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class RtEmissionGridTest {
    @Test
    void subSixteenPixelSpritesPopulateEveryGridCellWithoutHoles() {
        for (int size : new int[] {1, 4, 8, 12}) {
            RtEmissionGrid.Builder builder = new RtEmissionGrid.Builder(size, size);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    builder.add(x, y, 0.5f, 0.25f, 0.75f, 1.0f);
                }
            }
            RtEmissionGrid grid = builder.build();
            assertNotNull(grid, "grid should be non-null for " + size + "x" + size);
            for (int cy = 0; cy < RtEmissionGrid.SIZE; cy++) {
                for (int cx = 0; cx < RtEmissionGrid.SIZE; cx++) {
                    assertEquals(0.5f, grid.r(cx, cy), 1.0e-5f, "r at (" + cx + "," + cy + ") for size " + size);
                    assertEquals(0.25f, grid.g(cx, cy), 1.0e-5f, "g at (" + cx + "," + cy + ") for size " + size);
                    assertEquals(0.75f, grid.b(cx, cy), 1.0e-5f, "b at (" + cx + "," + cy + ") for size " + size);
                    assertEquals(1.0f, grid.weight(cx, cy), 1.0e-5f, "weight at (" + cx + "," + cy + ") for size " + size);
                }
            }
        }
    }
}
