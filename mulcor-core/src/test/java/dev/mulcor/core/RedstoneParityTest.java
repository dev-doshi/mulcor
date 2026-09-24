package dev.mulcor.core;

import static dev.mulcor.core.TestEngines.small;
import static org.junit.jupiter.api.Assertions.*;

import dev.mulcor.core.region.Entities;
import dev.mulcor.core.region.Region;
import dev.mulcor.registry.BlockData;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tick-by-tick parity with vanilla 26.2 redstone. Each {@code redstone/*.trace} resource was recorded on a real
 * vanilla server: the contraption is built with {@code /setblock}, triggered with {@code /setblock} between ticks,
 * and every probed block is read after every single tick ({@code /tick freeze} + {@code /tick step 1}). Mulcor runs
 * the same commands ({@link Engine#setBlockCommand}) and must produce the same states on every tick: wire power,
 * repeater delays, locking and pulse extension, torch inversion and burnout, lamp delays, TNT priming and fuse.
 *
 * <p>The scenarios sit inside one region, where Mulcor runs vanilla's update order exactly.
 *
 * <p>{@link #matchesDerivedTrace} runs the {@code redstone/derived/*.trace} scenarios (comparators, observers,
 * levers, buttons, repeater use) the same way. Their rows were worked out from the vanilla source, not recorded on a
 * server; they pin the port's behaviour until an oracle recording replaces them.
 */
class RedstoneParityTest {
    /** Scenario origin: inside one 32-block cell, clear of region borders for the scenario extent. */
    private static final int OX = 66, OZ = 66;
    /** The area the oracle cleared: x in [-2, 24], z in [-2, 12], y in [0, 6]; stone floor at y = -1. */
    private static final int EX = 24, EY = 6, EZ = 12;

    /** Action marker for a {@code use} line. */
    private static final String USE = "!use";

    record Trace(List<String[]> build, Map<Integer, List<String[]>> actions, Map<String, int[]> probes,
                 Map<Integer, Map<String, String>> rows, int end) {}

    @ParameterizedTest
    @ValueSource(strings = {"wire_line", "wire_sides", "lamp", "torch_inverter", "repeater_delays", "repeater_lock",
            "repeater_pulses", "torch_burnout", "tnt"})
    void matchesVanillaTrace(String name) throws IOException {
        run(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lever", "buttons", "observer", "comparator", "repeater_use"})
    void matchesDerivedTrace(String name) throws IOException {
        run("derived/" + name);
    }

    private static void run(String name) throws IOException {
        Trace tr = load(name);
        try (var e = new Engine(small().pillarDensity(0).chestsPerCell(0).build())) {
            int oy = e.world.surfaceY;
            for (int x = -2; x <= EX; x++) {
                for (int z = -2; z <= EZ; z++) {
                    e.world.blocks.set(OX + x, oy - 1, OZ + z, Blocks.STONE);
                    for (int y = 0; y <= EY; y++) e.world.blocks.set(OX + x, oy + y, OZ + z, Blocks.AIR);
                }
            }
            for (String[] b : tr.build) {
                assertTrue(e.setBlockCommand(OX + i(b[0]), oy + i(b[1]), OZ + i(b[2]), state(b[3])), "build " + String.join(" ", b));
            }
            e.run(4);
            Map<String, String> expected = new LinkedHashMap<>();
            for (int t = 0; t <= tr.end; t++) {
                for (String[] a : tr.actions.getOrDefault(t, List.of())) {
                    if (a[3].equals(USE)) assertTrue(e.useBlockCommand(OX + i(a[0]), oy + i(a[1]), OZ + i(a[2])), "use " + String.join(" ", a));
                    else e.setBlockCommand(OX + i(a[0]), oy + i(a[1]), OZ + i(a[2]), state(a[3]));
                }
                Map<String, String> row = tr.rows.get(t);
                if (row != null) expected.putAll(row);
                for (var p : tr.probes.entrySet()) {
                    int[] q = p.getValue();
                    String actual = describe(e.world.blocks.get(OX + q[0], oy + q[1], OZ + q[2]));
                    assertEquals(expected.get(p.getKey()), actual, name + ": " + p.getKey() + " at t=" + t);
                }
                assertEquals(expected.get("primed_tnt"), String.valueOf(primedTnt(e)), name + ": primed TNT at t=" + t);
                if (t < tr.end) e.tick();
            }
        }
    }

    /** The oracle's probe format: block name, plus the redstone properties it recorded. */
    static String describe(int state) {
        int block = BlockData.block(state);
        String n = BlockData.name(block);
        if (n.startsWith("minecraft:")) n = n.substring(10);
        return switch (n) {
            case "redstone_wire" -> n + "[power=" + prop(state, block, "power") + "]";
            case "redstone_torch", "redstone_wall_torch", "redstone_lamp" -> n + "[lit=" + prop(state, block, "lit") + "]";
            case "repeater" -> n + "[powered=" + prop(state, block, "powered") + ",locked=" + prop(state, block, "locked") + "]";
            case "comparator" -> n + "[mode=" + prop(state, block, "mode") + ",powered=" + prop(state, block, "powered") + "]";
            case "observer", "lever" -> n + "[powered=" + prop(state, block, "powered") + "]";
            default -> n.endsWith("_button") ? n + "[powered=" + prop(state, block, "powered") + "]" : n;
        };
    }

    private static String prop(int state, int block, String name) {
        int p = BlockData.property(block, name);
        return BlockData.valueName(p, BlockData.get(state, p));
    }

    private static int primedTnt(Engine e) {
        int n = 0;
        for (Region r : e.world.regions) {
            for (int s = 0; s < r.table.count(); s++) if (r.table.type(s) == Entities.TNT) n++;
        }
        return n;
    }

    /** {@code name[prop=value,...]} → vanilla state id (unspecified properties keep the block's default). */
    static int state(String spec) {
        String name = spec, props = "";
        int b = spec.indexOf('[');
        if (b >= 0) {
            name = spec.substring(0, b);
            props = spec.substring(b + 1, spec.length() - 1);
        }
        Map<String, String> m = new HashMap<>();
        for (String kv : props.split(",")) {
            if (kv.isEmpty()) continue;
            String[] p = kv.split("=", 2);
            m.put(p[0], p[1]);
        }
        int st = BlockData.parse(name, m);
        assertTrue(st >= 0, "unknown state " + spec);
        return st;
    }

    private static int i(String s) {
        return Integer.parseInt(s);
    }

    static Trace load(String name) throws IOException {
        String text;
        try (InputStream in = RedstoneParityTest.class.getResourceAsStream("redstone/" + name + ".trace")) {
            assertNotNull(in, name);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String[]> build = new ArrayList<>();
        Map<Integer, List<String[]>> actions = new HashMap<>();
        Map<String, int[]> probes = new LinkedHashMap<>();
        Map<Integer, Map<String, String>> rows = new HashMap<>();
        int end = -1;
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split(" ");
            switch (f[0]) {
                case "build" -> build.add(new String[] {f[1], f[2], f[3], f[4]});
                case "action" -> actions.computeIfAbsent(i(f[1]), k -> new ArrayList<>()).add(new String[] {f[2], f[3], f[4], f[5]});
                case "use" -> actions.computeIfAbsent(i(f[1]), k -> new ArrayList<>()).add(new String[] {f[2], f[3], f[4], USE});
                case "probe" -> probes.put(f[1], new int[] {i(f[2]), i(f[3]), i(f[4])});
                case "row" -> {
                    Map<String, String> row = new HashMap<>();
                    for (int k = 2; k < f.length; k++) {
                        String[] kv = f[k].split("=", 2);
                        row.put(kv[0], kv[1]);
                    }
                    rows.put(i(f[1]), row);
                }
                case "end" -> end = i(f[1]);
                default -> fail("bad trace line: " + line);
            }
        }
        return new Trace(build, actions, probes, rows, end);
    }
}
