package dev.comfyfluffy.caustica.rt.accel;

import java.util.List;

/** CPU state for one timeline-protected TLAS slot. Equality is exact, not a hash of GPU input. */
final class RtTlasCache {
    private Object writtenSource;
    private long writtenRevision;
    private int writtenCount = -1;
    private Object builtSource;
    private long builtRevision;
    private int builtBaseCount = -1;
    private long builtBlasEpoch;
    private int dynamicCount;
    private long[] addresses = new long[0];
    private int[] metadata = new int[0];
    private int[] transforms = new int[0];

    boolean staticRangeChanged(Object source, long revision, int count) {
        return writtenCount != count || writtenSource != source || writtenRevision != revision;
    }

    void staticRangeWritten(Object source, long revision, int count) {
        writtenSource = source;
        writtenRevision = revision;
        writtenCount = count;
    }

    boolean needsBuild(Object source, long revision, int baseCount,
                       List<RtAccel.Instance> dynamic, long blasEpoch) {
        if (builtBaseCount != baseCount || builtSource != source || builtRevision != revision
                || builtBlasEpoch != blasEpoch || dynamicCount != dynamic.size()) {
            return true;
        }
        for (int i = 0; i < dynamicCount; i++) {
            RtAccel.Instance instance = dynamic.get(i);
            int m = i * 3;
            if (addresses[i] != instance.blasDeviceAddress()
                    || metadata[m] != instance.customIndex() || metadata[m + 1] != instance.mask()
                    || metadata[m + 2] != instance.sbtRecordOffset()) return true;
            float[] matrix = instance.transform3x4();
            for (int j = 0; j < 12; j++) {
                if (transforms[i * 12 + j] != Float.floatToRawIntBits(matrix[j])) return true;
            }
        }
        return false;
    }

    /** Committed only after the encoder accepts the complete frame; the slot's graphics timeline guards reuse. */
    void built(Object source, long revision, int baseCount, List<RtAccel.Instance> dynamic, long blasEpoch) {
        int count = dynamic.size();
        if (addresses.length < count) {
            int capacity = Math.max(count, Math.max(16, addresses.length * 2));
            addresses = new long[capacity];
            metadata = new int[Math.multiplyExact(capacity, 3)];
            transforms = new int[Math.multiplyExact(capacity, 12)];
        }
        for (int i = 0; i < count; i++) {
            RtAccel.Instance instance = dynamic.get(i);
            addresses[i] = instance.blasDeviceAddress();
            int m = i * 3;
            metadata[m] = instance.customIndex();
            metadata[m + 1] = instance.mask();
            metadata[m + 2] = instance.sbtRecordOffset();
            float[] matrix = instance.transform3x4();
            for (int j = 0; j < 12; j++) transforms[i * 12 + j] = Float.floatToRawIntBits(matrix[j]);
        }
        builtSource = source;
        builtRevision = revision;
        builtBaseCount = baseCount;
        builtBlasEpoch = blasEpoch;
        dynamicCount = count;
    }
}
