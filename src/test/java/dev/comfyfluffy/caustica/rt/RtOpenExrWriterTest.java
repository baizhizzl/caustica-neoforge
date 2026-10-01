package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtOpenExrWriterTest {
    @TempDir
    Path temp;

    @Test
    void writesValidUncompressedHalfScanlinesWithTopRowFirst() throws IOException {
        // Vulkan row order: bottom row first, RGBA interleaved.
        short[] pixels = halves(
                1, 2, 3, 4,     5, 6, 7, 8,
                9, 10, 11, 12,  13, 14, 15, 16);
        Path output = temp.resolve("capture.exr");
        RtOpenExrWriter.write(output, 2, 2, pixels, new RtOpenExrWriter.Metadata(
                0.25f, 1.5f, 0.375f, "auto", 12.0f, -1.5f, -1.75f, "none", 42L));

        ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(output)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(20_000_630, file.getInt());
        assertEquals(2, file.getInt());

        Map<String, Attribute> attributes = readAttributes(file);
        assertEquals("float", attributes.get("causticaResidualExposure").type());
        assertEquals(1.5f, attributes.get("causticaResidualExposure").value().getFloat(0));
        assertEquals(0.375f, attributes.get("causticaAbsoluteExposure").value().getFloat(0));
        assertEquals("ACEScg (AP1/D60), scene-linear",
                StandardCharsets.UTF_8.decode(attributes.get("causticaColorSpace").value().duplicate()).toString());
        assertEquals(32, attributes.get("chromaticities").value().remaining());

        long firstOffset = file.getLong();
        long secondOffset = file.getLong();
        assertEquals(file.position(), firstOffset);
        assertEquals(firstOffset + 24, secondOffset);

        assertScanline(file, 0, halves(12, 16, 11, 15, 10, 14, 9, 13));
        assertScanline(file, 1, halves(4, 8, 3, 7, 2, 6, 1, 5));
        assertEquals(file.limit(), file.position());
    }

    private static Map<String, Attribute> readAttributes(ByteBuffer file) {
        Map<String, Attribute> result = new HashMap<>();
        while (file.get(file.position()) != 0) {
            String name = cString(file);
            String type = cString(file);
            int size = file.getInt();
            ByteBuffer value = file.slice(file.position(), size).order(ByteOrder.LITTLE_ENDIAN);
            file.position(file.position() + size);
            result.put(name, new Attribute(type, value));
        }
        file.get();
        assertTrue(result.containsKey("channels"));
        assertTrue(result.containsKey("dataWindow"));
        return result;
    }

    private static void assertScanline(ByteBuffer file, int expectedY, short[] expectedChannelMajor) {
        assertEquals(expectedY, file.getInt());
        assertEquals(expectedChannelMajor.length * Short.BYTES, file.getInt());
        short[] actual = new short[expectedChannelMajor.length];
        file.asShortBuffer().get(actual);
        file.position(file.position() + actual.length * Short.BYTES);
        assertArrayEquals(expectedChannelMajor, actual);
    }

    private static String cString(ByteBuffer bytes) {
        int start = bytes.position();
        int end = start;
        while (bytes.get(end) != 0) {
            end++;
        }
        byte[] encoded = new byte[end - start];
        bytes.get(encoded);
        bytes.get();
        return new String(encoded, StandardCharsets.US_ASCII);
    }

    private static short[] halves(float... values) {
        short[] result = new short[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = Float.floatToFloat16(values[i]);
        }
        return result;
    }

    private record Attribute(String type, ByteBuffer value) {
    }
}
