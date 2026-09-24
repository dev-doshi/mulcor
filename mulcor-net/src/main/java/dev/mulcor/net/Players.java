package dev.mulcor.net;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Online players by entity id, for what one session needs to know about the others: their profile (a player entity
 * can only be spawned on a client once its tab-list entry exists) and a way to reach their connection. Indexed by
 * entity id without boxing; joins and leaves are cold, lookups happen while tracking entities.
 */
public final class Players {
    private final AtomicReferenceArray<PlaySession> byEntity;

    public Players(int maxEntities) {
        this.byEntity = new AtomicReferenceArray<>(maxEntities);
    }

    public int capacity() { return byEntity.length(); }

    /** The session of the player with this entity id, or null. */
    public PlaySession get(int entity) {
        return entity >= 0 && entity < byEntity.length() ? byEntity.get(entity) : null;
    }

    void add(PlaySession s) {
        byEntity.set(s.entity(), s);
    }

    void remove(PlaySession s) {
        byEntity.compareAndSet(s.entity(), s, null);
    }
}
