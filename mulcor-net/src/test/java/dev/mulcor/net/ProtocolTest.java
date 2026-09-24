package dev.mulcor.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.Blocks;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolTest {
    @Test
    void resolvesDistinctPlayPacketIdsWithoutStartingMinestom() {
        var ids = List.of(Protocol.DIG, Protocol.POSITION, Protocol.PLACE, Protocol.CLICK_WINDOW);
        System.out.println("play ids: dig=" + Protocol.DIG + " position=" + Protocol.POSITION + " place=" + Protocol.PLACE
                + " click=" + Protocol.CLICK_WINDOW + " palette bits=" + Protocol.PALETTE_MIN_BITS + ".." + Protocol.PALETTE_MAX_BITS
                + " direct=" + Protocol.PALETTE_DIRECT_BITS);
        assertEquals(4, new HashSet<>(ids).size());
        for (int id : ids) assertTrue(id >= 0 && id < 256);
    }

    @Test
    void mapsBlockStates() {
        assertEquals(0, Protocol.vanillaState(Blocks.AIR));
        assertNotEquals(Protocol.vanillaState(Blocks.STONE), Protocol.vanillaState(Blocks.DIRT));
        var wires = new HashSet<Integer>();
        for (int p = 0; p < 16; p++) wires.add(Protocol.vanillaState(Blocks.WIRE + p));
        assertEquals(16, wires.size(), "each redstone power level is a distinct vanilla state");
    }
}
