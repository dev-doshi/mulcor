package dev.mulcor.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * One region's live entities, stored as struct-of-arrays in native memory and densely packed so that the
 * physics loop walks each column linearly.
 *
 * <p>Removal swaps the last entity into the hole. {@link #remove} returns the id of the moved entity so the
 * caller can fix its {@link EntityDirectory} slot. The table is confined to the thread running its region.
 */
public final class EntityTable {
    private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;
    private static final ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT;
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

    private final int capacity;
    private final MemorySegment seg;
    private final long idCol, xCol, yCol, zCol, vxCol, vyCol, vzCol, typeCol, flagsCol, aux0Col, aux1Col, aux2Col, yawCol, fwdCol;
    private int count;

    public EntityTable(NativeMemory memory, int capacity) {
        this.capacity = capacity;
        long c = Mem.align(capacity, 16);
        long off = 0;
        idCol = off;   off += c * 8;
        xCol = off;    off += c * 8;
        yCol = off;    off += c * 8;
        zCol = off;    off += c * 8;
        vxCol = off;   off += c * 8;
        vyCol = off;   off += c * 8;
        vzCol = off;   off += c * 8;
        typeCol = off; off += c * 4;
        flagsCol = off; off += c * 4;
        aux0Col = off; off += c * 4;
        aux1Col = off; off += c * 4;
        aux2Col = off; off += c * 4;
        yawCol = off;  off += c * 4;
        fwdCol = off;  off += c * 4;
        this.seg = memory.allocate(off);
    }

    public int capacity() { return capacity; }
    public int count() { return count; }
    public boolean isFull() { return count == capacity; }

    /** Append an entity and return its slot, or {@code -1} if the table is full. */
    public int add(long id, double x, double y, double z, int type) {
        if (count == capacity) {
            return -1;
        }
        int s = count++;
        seg.set(L, idCol + s * 8L, id);
        seg.set(D, xCol + s * 8L, x);
        seg.set(D, yCol + s * 8L, y);
        seg.set(D, zCol + s * 8L, z);
        seg.set(D, vxCol + s * 8L, 0.0);
        seg.set(D, vyCol + s * 8L, 0.0);
        seg.set(D, vzCol + s * 8L, 0.0);
        seg.set(I, typeCol + s * 4L, type);
        seg.set(I, flagsCol + s * 4L, 0);
        seg.set(I, aux0Col + s * 4L, 0);
        seg.set(I, aux1Col + s * 4L, 0);
        seg.set(I, aux2Col + s * 4L, 0);
        seg.set(F, yawCol + s * 4L, 0f);
        seg.set(F, fwdCol + s * 4L, 0f);
        return s;
    }

    /** Append an entity from an {@link EntityRecord}. Returns its slot, or {@code -1} if full. */
    public int addRecord(MemorySegment src, long off) {
        if (count == capacity) {
            return -1;
        }
        int s = count++;
        seg.set(L, idCol + s * 8L, src.get(L, off + EntityRecord.ID));
        seg.set(D, xCol + s * 8L, src.get(D, off + EntityRecord.X));
        seg.set(D, yCol + s * 8L, src.get(D, off + EntityRecord.Y));
        seg.set(D, zCol + s * 8L, src.get(D, off + EntityRecord.Z));
        seg.set(D, vxCol + s * 8L, src.get(D, off + EntityRecord.VX));
        seg.set(D, vyCol + s * 8L, src.get(D, off + EntityRecord.VY));
        seg.set(D, vzCol + s * 8L, src.get(D, off + EntityRecord.VZ));
        seg.set(I, typeCol + s * 4L, src.get(I, off + EntityRecord.TYPE));
        seg.set(I, flagsCol + s * 4L, src.get(I, off + EntityRecord.FLAGS));
        seg.set(I, aux0Col + s * 4L, src.get(I, off + EntityRecord.AUX0));
        seg.set(I, aux1Col + s * 4L, src.get(I, off + EntityRecord.AUX1));
        seg.set(I, aux2Col + s * 4L, src.get(I, off + EntityRecord.AUX2));
        seg.set(F, yawCol + s * 4L, src.get(F, off + EntityRecord.IN_YAW));
        seg.set(F, fwdCol + s * 4L, src.get(F, off + EntityRecord.IN_FWD));
        return s;
    }

    /** Serialize a slot into an {@link EntityRecord} at {@code dst[off]}. */
    public void writeRecord(int s, MemorySegment dst, long off) {
        dst.set(L, off + EntityRecord.ID, id(s));
        dst.set(D, off + EntityRecord.X, x(s));
        dst.set(D, off + EntityRecord.Y, y(s));
        dst.set(D, off + EntityRecord.Z, z(s));
        dst.set(D, off + EntityRecord.VX, vx(s));
        dst.set(D, off + EntityRecord.VY, vy(s));
        dst.set(D, off + EntityRecord.VZ, vz(s));
        dst.set(I, off + EntityRecord.TYPE, type(s));
        dst.set(I, off + EntityRecord.FLAGS, flags(s));
        dst.set(I, off + EntityRecord.AUX0, aux0(s));
        dst.set(I, off + EntityRecord.AUX1, aux1(s));
        dst.set(I, off + EntityRecord.AUX2, aux2(s));
        dst.set(F, off + EntityRecord.IN_YAW, inputYaw(s));
        dst.set(F, off + EntityRecord.IN_FWD, inputForward(s));
    }

    /**
     * Remove slot {@code s} by moving the last entity into it. Returns the id of the entity that now occupies
     * {@code s}, or {@code -1} if {@code s} was the last slot.
     */
    public long remove(int s) {
        int last = --count;
        if (s == last) {
            return -1;
        }
        long movedId = id(last);
        seg.set(L, idCol + s * 8L, movedId);
        seg.set(D, xCol + s * 8L, x(last));
        seg.set(D, yCol + s * 8L, y(last));
        seg.set(D, zCol + s * 8L, z(last));
        seg.set(D, vxCol + s * 8L, vx(last));
        seg.set(D, vyCol + s * 8L, vy(last));
        seg.set(D, vzCol + s * 8L, vz(last));
        seg.set(I, typeCol + s * 4L, type(last));
        seg.set(I, flagsCol + s * 4L, flags(last));
        seg.set(I, aux0Col + s * 4L, aux0(last));
        seg.set(I, aux1Col + s * 4L, aux1(last));
        seg.set(I, aux2Col + s * 4L, aux2(last));
        seg.set(F, yawCol + s * 4L, inputYaw(last));
        seg.set(F, fwdCol + s * 4L, inputForward(last));
        return movedId;
    }

    public void clear() { count = 0; }

    public long id(int s) { return seg.get(L, idCol + s * 8L); }
    public double x(int s) { return seg.get(D, xCol + s * 8L); }
    public double y(int s) { return seg.get(D, yCol + s * 8L); }
    public double z(int s) { return seg.get(D, zCol + s * 8L); }
    public double vx(int s) { return seg.get(D, vxCol + s * 8L); }
    public double vy(int s) { return seg.get(D, vyCol + s * 8L); }
    public double vz(int s) { return seg.get(D, vzCol + s * 8L); }
    public int type(int s) { return seg.get(I, typeCol + s * 4L); }
    public int flags(int s) { return seg.get(I, flagsCol + s * 4L); }
    public int aux0(int s) { return seg.get(I, aux0Col + s * 4L); }
    public int aux1(int s) { return seg.get(I, aux1Col + s * 4L); }
    public int aux2(int s) { return seg.get(I, aux2Col + s * 4L); }
    /** Movement input, as vanilla mob AI leaves it for {@code travel}: body yaw (degrees) and forward (zza). */
    public float inputYaw(int s) { return seg.get(F, yawCol + s * 4L); }
    public float inputForward(int s) { return seg.get(F, fwdCol + s * 4L); }

    public void setInput(int s, float yaw, float forward) {
        seg.set(F, yawCol + s * 4L, yaw);
        seg.set(F, fwdCol + s * 4L, forward);
    }

    public void setPos(int s, double x, double y, double z) {
        seg.set(D, xCol + s * 8L, x);
        seg.set(D, yCol + s * 8L, y);
        seg.set(D, zCol + s * 8L, z);
    }

    public void setVel(int s, double vx, double vy, double vz) {
        seg.set(D, vxCol + s * 8L, vx);
        seg.set(D, vyCol + s * 8L, vy);
        seg.set(D, vzCol + s * 8L, vz);
    }

    public void setFlags(int s, int v) { seg.set(I, flagsCol + s * 4L, v); }
    public void setAux0(int s, int v) { seg.set(I, aux0Col + s * 4L, v); }
    public void setAux1(int s, int v) { seg.set(I, aux1Col + s * 4L, v); }
    public void setAux2(int s, int v) { seg.set(I, aux2Col + s * 4L, v); }
}
