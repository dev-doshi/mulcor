package dev.mulcor.harness;

/**
 * A source of client inputs. Implementations deliver the same 32-byte input records that the network decoder
 * produces, so the engine cannot tell a virtual client from a socket.
 *
 * <p>Instances are single-threaded: give each producer thread its own.
 */
public interface VirtualClientProvider {
    /** Returns false when the engine pushed back (the target region's ingress ring is full). */
    boolean send(int kind, int entity, int x, int y, int z, int a, int b, int c);

    long accepted();

    long rejected();
}
