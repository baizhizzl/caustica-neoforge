package dev.comfyfluffy.caustica.rt.entity;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class RtEntityCaptureTest {
    private static final float[] X = {0f, 1f, 1f, 0f};
    private static final float[] Y = {0f, 0f, 1f, 1f};
    private static final float[] Z = {0f, 0f, 0f, 0f};
    private static final float[] U = {0f, 1f, 1f, 0f};
    private static final float[] V = {0f, 0f, 1f, 1f};

    @Test
    void packsTrianglesIntoFixedAlphaBucketOrder() {
        RtEntityCapture capture = new RtEntityCapture();
        addQuad(capture, RtAccel.ENTITY_BUCKET_ANY_HIT, 22);
        addQuad(capture, RtAccel.ENTITY_BUCKET_OPAQUE, 11);
        addQuad(capture, RtAccel.ENTITY_BUCKET_ANY_HIT, 33);

        RtEntityCapture.PackedGeometry packed = capture.packGeometry();

        assertArrayEquals(new int[] {2, 4}, packed.bucketTris());
        assertArrayEquals(new int[] {
                4, 5, 6, 4, 6, 7,
                0, 1, 2, 0, 2, 3,
                8, 9, 10, 8, 10, 11
        }, packed.indices().toIntArray());
        assertMaterialIds(packed, 11, 11, 22, 22, 33, 33);
    }

    @Test
    void resetClearsBucketMetadata() {
        RtEntityCapture capture = new RtEntityCapture();
        addQuad(capture, RtAccel.ENTITY_BUCKET_OPAQUE, 11);

        capture.reset();

        assertArrayEquals(new int[] {0, 0}, capture.packGeometry().bucketTris());
    }

    @Test
    void blockEntityMeshHashDetectsGlyphUvChangesWithIdenticalGeometry() {
        RtEntityCapture original = new RtEntityCapture();
        RtEntityCapture updated = new RtEntityCapture();
        addQuad(original, RtAccel.ENTITY_BUCKET_ANY_HIT, 11);
        addQuad(updated, RtAccel.ENTITY_BUCKET_ANY_HIT, 11);
        long originalHash = original.meshHash();
        assertEquals(originalHash, updated.meshHash());

        // Equal-size glyphs on one font atlas keep their positions and shading but sample another tile.
        for (int vertex = 0; vertex < 4; vertex++) {
            updated.uvList.set(vertex * 2, updated.uvList.getFloat(vertex * 2) + 0.125f);
        }

        assertArrayEquals(original.verts.toFloatArray(), updated.verts.toFloatArray());
        assertArrayEquals(original.idx.toIntArray(), updated.idx.toIntArray());
        assertArrayEquals(original.prim.toFloatArray(), updated.prim.toFloatArray());
        assertArrayEquals(original.alphaBuckets.toIntArray(), updated.alphaBuckets.toIntArray());
        assertNotEquals(originalHash, updated.meshHash());
    }

    @Test
    void blockEntityMeshHashIgnoresDiscardedBufferContents() {
        RtEntityCapture capture = new RtEntityCapture();
        addQuad(capture, RtAccel.ENTITY_BUCKET_ANY_HIT, 11);
        long expectedHash = capture.meshHash();
        addQuad(capture, RtAccel.ENTITY_BUCKET_OPAQUE, 22);

        capture.reset();
        addQuad(capture, RtAccel.ENTITY_BUCKET_ANY_HIT, 11);

        assertEquals(expectedHash, capture.meshHash());
    }

    @Test
    void streamingVertexConsumerBuffersFourVerticesIntoAQuad() {
        RtEntityCapture capture = new RtEntityCapture();
        capture.currentAlphaBucket = RtAccel.ENTITY_BUCKET_OPAQUE;
        capture.currentMaterialId = 7;

        capture.addVertex(0f, 0f, 0f).setColor(255, 128, 64, 255).setUv(0f, 0f).setNormal(0f, 0f, 1f);
        capture.addVertex(1f, 0f, 0f).setColor(255, 128, 64, 255).setUv(1f, 0f).setNormal(0f, 0f, 1f);
        capture.addVertex(1f, 1f, 0f).setColor(255, 128, 64, 255).setUv(1f, 1f).setNormal(0f, 0f, 1f);
        capture.addVertex(0f, 1f, 0f).setColor(255, 128, 64, 255).setUv(0f, 1f).setNormal(0f, 0f, 1f);
        capture.finishStreamedVertices();

        RtEntityCapture.PackedGeometry packed = capture.packGeometry();
        assertArrayEquals(new int[] {2, 0}, packed.bucketTris());
        assertEquals(12, capture.verts.size());
        assertEquals(0f, packed.primitives().getFloat(0), 1.0e-6f);
        assertEquals(0f, packed.primitives().getFloat(1), 1.0e-6f);
        assertEquals(1f, packed.primitives().getFloat(2), 1.0e-6f);
        assertEquals(1f, packed.primitives().getFloat(4), 1.0e-5f);
        assertEquals(128f / 255f, packed.primitives().getFloat(5), 1.0e-5f);
    }

    @Test
    void degenerateFirstTriangleFallsBackToSecondTriangleNormal() {
        RtEntityCapture capture = new RtEntityCapture();
        capture.currentAlphaBucket = RtAccel.ENTITY_BUCKET_OPAQUE;
        // First triangle (0, 1, 2) is degenerate (v1 == v2), second triangle (0, 2, 3) has valid area.
        float[] dx = {0f, 1f, 1f, 0f};
        float[] dy = {0f, 0f, 0f, 1f};
        float[] dz = {0f, 0f, 0f, 0f};
        capture.addDirectQuad(dx, dy, dz, U, V, 0f, 0f, 0f, -1);

        float nx = capture.prim.getFloat(0);
        float ny = capture.prim.getFloat(1);
        float nz = capture.prim.getFloat(2);
        assertEquals(1f, Math.sqrt(nx * nx + ny * ny + nz * nz), 1.0e-5f);
        assertEquals(1f, Math.abs(nz), 1.0e-5f);
    }

    private static void addQuad(RtEntityCapture capture, int bucket, int materialId) {
        capture.currentAlphaBucket = bucket;
        capture.currentMaterialId = materialId;
        capture.addDirectQuad(X, Y, Z, U, V, 0f, 0f, 1f, -1);
    }

    private static void assertMaterialIds(RtEntityCapture.PackedGeometry packed, int... expected) {
        assertEquals(expected.length * 12, packed.primitives().size());
        for (int triangle = 0; triangle < expected.length; triangle++) {
            int actual = Float.floatToRawIntBits(packed.primitives().getFloat(triangle * 12 + 8));
            assertEquals(expected[triangle], actual, "material id for packed triangle " + triangle);
        }
    }
}
