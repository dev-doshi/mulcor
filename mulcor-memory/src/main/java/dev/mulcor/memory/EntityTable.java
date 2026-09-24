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
    private final long idCol, xCol, yCol, zCol, vxCol, vyCol, vzCol, typeCol, flagsCol, aux0Col, aux1Col, aux2Col;
    private int count;

    public EntityTable(NativeMemory memory, int capacity) {
        this.capacity = capacity;
        long c = Mem.align(capacity, 16);
        long off = 0;
        idCol = off;   off += c * 8;
        xCol = off;    off += c * 8;
        yCol = off;    off += c * 8;
        zCol = off;    off += c * 8;
        vxCol = off;   off += c * 4;
        vyCol = off;   off += c * 4;
        vzCol = off;   off += c * 4;
        typeCol = off; off += c * 4;
        flagsCol = off; off += c * 4;
        aux0Col = off; off += c * 4;
        aux1Col = off; off += c * 4;
        aux2Col = off; off += c * 4;
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
        seg.set(F, vxCol + s * 4L, 0f);
        seg.set(F, vyCol + s * 4L, 0f);
        seg.set(F, vzCol + s * 4L, 0f);
        seg.set(I, typeCol + s * 4L, type);
        seg.set(I, flagsCol + s * 4L, 0);
        seg.set(I, aux0Col + s * 4L, 0);
        seg.set(I, aux1Col + s * 4L, 0);
        seg.set(I, aux2Col + s * 4L, 0);
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
        seg.set(F, vxCol + s * 4L, src.get(F, off + EntityRecord.VX));
        seg.set(F, vyCol + s * 4L, src.get(F, off + EntityRecord.VY));
        seg.set(F, vzCol + s * 4L, src.get(F, off + EntityRecord.VZ));
        seg.set(I, typeCol + s * 4L, src.get(I, off + EntityRecord.TYPE));
        seg.set(I, flagsCol + s * 4L, src.get(I, off + EntityRecord.FLAGS));
        seg.set(I, aux0Col + s * 4L, src.get(I, off + EntityRecord.AUX0));
        seg.set(I, aux1Col + s * 4L, src.get(I, off + EntityRecord.AUX1));
        seg.set(I, aux2Col + s * 4L, src.get(I, off + EntityRecord.AUX2));
        return s;
    }

    /** Serialize a slot into an {@link EntityRecord} at {@code dst[off]}. */
    public void writeRecord(int s, MemorySegment dst, long off) {
        dst.set(L, off + EntityRecord.ID, id(s));
        dst.set(D, off + EntityRecord.X, x(s));
        dst.set(D, off + EntityRecord.Y, y(s));
        dst.set(D, off + EntityRecord.Z, z(s));
        dst.set(F, off + EntityRecord.VX, vx(s));
        dst.set(F, off + EntityRecord.VY, vy(s));
        dst.set(F, off + EntityRecord.VZ, vz(s));
        dst.set(I, off + EntityRecord.TYPE, type(s));
        dst.set(I, off + EntityRecord.FLAGS, flags(s));
        dst.set(I, off + EntityRecord.AUX0, aux0(s));
        dst.set(I, off + EntityRecord.AUX1, aux1(s));
        dst.set(I, off + EntityRecord.AUX2, aux2(s));
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
        seg.set(F, vxCol + s * 4L, vx(last));
        seg.set(F, vyCol + s * 4L, vy(last));
        seg.set(F, vzCol + s * 4L, vz(last));
        seg.set(I, typeCol + s * 4L, type(last));
        seg.set(I, flagsCol + s * 4L, flags(last));
        seg.set(I, aux0Col + s * 4L, aux0(last));
        seg.set(I, aux1Col + s * 4L, aux1(last));
        seg.set(I, aux2Col + s * 4L, aux2(last));
        return movedId;
    }

    public void clear() { count = 0; }

    public long id(int s) { return seg.get(L, idCol + s * 8L); }
    public double x(int s) { return seg.get(D, xCol + s * 8L); }
    public double y(int s) { return seg.get(D, yCol + s * 8L); }
    public double z(int s) { return seg.get(D, zCol + s * 8L); }
    public float vx(int s) { return seg.get(F, vxCol + s * 4L); }
    public float vy(int s) { return seg.get(F, vyCol + s * 4L); }
    public float vz(int s) { return seg.get(F, vzCol + s * 4L); }
    public int type(int s) { return seg.get(I, typeCol + s * 4L); }
    public int flags(int s) { return seg.get(I, flagsCol + s * 4L); }
    public int aux0(int s) { return seg.get(I, aux0Col + s * 4L); }
    public int aux1(int s) { return seg.get(I, aux1Col + s * 4L); }
    public int aux2(int s) { return seg.get(I, aux2Col + s * 4L); }

    public void setPos(int s, double x, double y, double z) {
        seg.set(D, xCol + s * 8L, x);
        seg.set(D, yCol + s * 8L, y);
        seg.set(D, zCol + s * 8L, z);
    }

    public void setVel(int s, float vx, float vy, float vz) {
        seg.set(F, vxCol + s * 4L, vx);
        seg.set(F, vyCol + s * 4L, vy);
        seg.set(F, vzCol + s * 4L, vz);
    }

    public void setFlags(int s, int v) { seg.set(I, flagsCol + s * 4L, v); }
    public void setAux0(int s, int v) { seg.set(I, aux0Col + s * 4L, v); }
    public void setAux1(int s, int v) { seg.set(I, aux1Col + s * 4L, v); }
    public void setAux2(int s, int v) { seg.set(I, aux2Col + s * 4L, v); }
}
