package dev.mulcor.harness;

import dev.mulcor.core.Engine;
import dev.mulcor.core.region.Input;
import dev.mulcor.memory.NativeMemory;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Bypasses sockets and Netty entirely: it encodes each input into a thread-private off-heap record and hands it
 * straight to {@link Engine#submitInput}, the same ingress rings the network path feeds. Allocation-free per send.
 */
public final class HeadlessVirtualClientProvider implements VirtualClientProvider {
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

    private final Engine engine;
    private final MemorySegment record;
    private long accepted, rejected;

    public HeadlessVirtualClientProvider(Engine engine) {
        this.engine = engine;
        this.record = NativeMemory.auto().allocate(Input.BYTES);
    }

    @Override
    public boolean send(int kind, int entity, int x, int y, int z, int a, int b, int c) {
        record.set(I, Input.KIND, kind);
        record.set(I, Input.ENTITY, entity);
        record.set(I, Input.X, x);
        record.set(I, Input.Y, y);
        record.set(I, Input.Z, z);
        record.set(I, Input.A, a);
        record.set(I, Input.B, b);
        record.set(I, Input.C, c);
        if (engine.submitInput(record, 0)) {
            accepted++;
            return true;
        }
        rejected++;
        return false;
    }

    @Override
    public long accepted() { return accepted; }

    @Override
    public long rejected() { return rejected; }
}
