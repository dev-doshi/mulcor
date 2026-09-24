package dev.mulcor.registry.gen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Build-time generator: turns Minestom's bundled vanilla data ({@code net/minestom/data/*.json}, extracted from the
 * vanilla server's data generator) into {@code registry.bin} plus Java constant classes. Every value comes straight
 * from vanilla; nothing is hand-maintained. The binary layout is read by {@code dev.mulcor.registry.Registry}.
 */
public final class RegistryGen {
    static final int MAGIC = 0x4D524547; // "MREG"
    static final int VERSION = 1;

    // Per-state flag bits (mirrored by BlockData).
    static final int SOLID = 1, SOLID_BLOCKING = 1 << 1, BLOCKS_MOTION = 1 << 2, REDSTONE_CONDUCTOR = 1 << 3,
            FLUID = 1 << 4, FLAMMABLE = 1 << 5, CAN_RESPAWN_IN = 1 << 6, AIR = 1 << 7, LIQUID = 1 << 8,
            REPLACEABLE = 1 << 9, OCCLUDES = 1 << 10, SIGNAL_SOURCE = 1 << 11, REQUIRES_TOOL = 1 << 12,
            GRAVITY = 1 << 13;

    private static final Pattern AABB = Pattern.compile(
            "AABB\\[([-0-9.E]+), ([-0-9.E]+), ([-0-9.E]+)\\] -> \\[([-0-9.E]+), ([-0-9.E]+), ([-0-9.E]+)\\]");

    private final Map<String, Integer> shapeIndex = new HashMap<>();
    private final List<double[]> shapes = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        new RegistryGen().run(out);
    }

    private static JsonObject load(String name) throws IOException {
        String path = "/net/minestom/data/" + name + ".json";
        try (InputStream in = RegistryGen.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing vanilla data " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** Entries sorted by their "id" field, which must be dense from 0. */
    private static List<Map.Entry<String, JsonElement>> byId(JsonObject o) {
        List<Map.Entry<String, JsonElement>> list = new ArrayList<>(o.entrySet());
        list.sort((a, b) -> Integer.compare(a.getValue().getAsJsonObject().get("id").getAsInt(),
                b.getValue().getAsJsonObject().get("id").getAsInt()));
        for (int i = 0; i < list.size(); i++) {
            int id = list.get(i).getValue().getAsJsonObject().get("id").getAsInt();
            if (id != i) throw new IllegalStateException("ids not dense at " + list.get(i).getKey());
        }
        return list;
    }

    private int shape(String text) {
        return shapeIndex.computeIfAbsent(text, t -> {
            List<Double> v = new ArrayList<>();
            Matcher m = AABB.matcher(t);
            while (m.find()) for (int g = 1; g <= 6; g++) v.add(Double.parseDouble(m.group(g)));
            if (v.isEmpty() && !t.equals("[]")) throw new IllegalStateException("unparsed shape " + t);
            double[] d = new double[v.size()];
            for (int i = 0; i < d.length; i++) d[i] = v.get(i);
            shapes.add(d);
            return shapes.size() - 1;
        });
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        JsonElement e = o.get(k);
        return e == null ? def : e.getAsBoolean();
    }

    private static float flt(JsonObject o, String k, float def) {
        JsonElement e = o.get(k);
        return e == null ? def : e.getAsFloat();
    }

    private static int integer(JsonObject o, String k, int def) {
        JsonElement e = o.get(k);
        return e == null ? def : e.getAsInt();
    }

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    private static int flags(JsonObject s, JsonObject b) {
        int f = 0;
        if (bool(s, "solid", bool(b, "solid", false))) f |= SOLID;
        if (bool(s, "solidBlocking", bool(b, "solidBlocking", false))) f |= SOLID_BLOCKING;
        if (bool(s, "blocksMotion", bool(b, "blocksMotion", false))) f |= BLOCKS_MOTION;
        if (bool(s, "redstoneConductor", bool(b, "redstoneConductor", false))) f |= REDSTONE_CONDUCTOR;
        if (bool(s, "fluid", bool(b, "fluid", false))) f |= FLUID;
        if (bool(s, "flammable", bool(b, "flammable", false))) f |= FLAMMABLE;
        if (bool(s, "canRespawnIn", bool(b, "canRespawnIn", false))) f |= CAN_RESPAWN_IN;
        if (bool(s, "air", bool(b, "air", false))) f |= AIR;
        if (bool(s, "liquid", bool(b, "liquid", false))) f |= LIQUID;
        if (bool(s, "replaceable", bool(b, "replaceable", false))) f |= REPLACEABLE;
        if (bool(s, "occludes", bool(b, "occludes", false))) f |= OCCLUDES;
        if (bool(s, "signalSource", bool(b, "signalSource", false))) f |= SIGNAL_SOURCE;
        if (bool(s, "requiresTool", bool(b, "requiresTool", false))) f |= REQUIRES_TOOL;
        if (bool(s, "gravity", bool(b, "gravity", false))) f |= GRAVITY;
        return f;
    }

    private static int pushReaction(String s) {
        return switch (s) {
            case "NORMAL" -> 0;
            case "DESTROY" -> 1;
            case "BLOCK" -> 2;
            case "IGNORE" -> 3;
            case "PUSH_ONLY" -> 4;
            default -> throw new IllegalStateException("push reaction " + s);
        };
    }

    void run(Path out) throws IOException {
        JsonObject constants = load("constants");
        var blocks = byId(load("block"));
        var items = byId(load("item"));
        var entities = byId(load("entity_type"));
        var attributes = byId(load("attribute"));
        var blockEntities = byId(load("block_entity_type"));
        var sounds = byId(load("sound_event"));
        var particles = byId(load("particle_type"));
        var fluids = byId(load("fluid"));

        Map<String, Integer> itemIds = new HashMap<>(), blockIds = new HashMap<>(), attrIds = new HashMap<>();
        for (int i = 0; i < items.size(); i++) itemIds.put(items.get(i).getKey(), i);
        for (int i = 0; i < blocks.size(); i++) blockIds.put(blocks.get(i).getKey(), i);
        for (int i = 0; i < attributes.size(); i++) attrIds.put(attributes.get(i).getKey(), i);

        int stateCount = 0;
        for (var e : blocks) stateCount += e.getValue().getAsJsonObject().getAsJsonObject("states").size();
        int[] stFlags = new int[stateCount], stBlock = new int[stateCount];
        int[] stEmission = new int[stateCount], stOpacity = new int[stateCount];
        int[] stCollision = new int[stateCount], stOutline = new int[stateCount], stOcclusion = new int[stateCount];
        int[] stInteraction = new int[stateCount];
        shape("[]"); // shape 0 = empty

        Path res = out.resolve("resources/dev/mulcor/registry/registry.bin");
        Files.createDirectories(res.getParent());
        try (OutputStream fo = Files.newOutputStream(res);
                DataOutputStream d = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(fo)))) {
            d.writeInt(MAGIC);
            d.writeInt(VERSION);
            d.writeUTF(constants.get("name").getAsString());
            d.writeInt(constants.get("protocol").getAsInt());
            d.writeInt(constants.get("world").getAsInt());

            // ---- blocks ----
            d.writeInt(blocks.size());
            for (int id = 0; id < blocks.size(); id++) {
                String name = blocks.get(id).getKey();
                JsonObject b = blocks.get(id).getValue().getAsJsonObject();
                JsonObject states = b.getAsJsonObject("states");
                int first = Integer.MAX_VALUE;
                for (var s : states.entrySet()) first = Math.min(first, s.getValue().getAsJsonObject().get("stateId").getAsInt());
                // Properties in declaration order; the last varies fastest (vanilla's StateDefinition order).
                LinkedHashMap<String, List<String>> props = new LinkedHashMap<>();
                JsonObject p = b.getAsJsonObject("properties");
                if (p != null) {
                    for (var e : p.entrySet()) {
                        List<String> values = new ArrayList<>();
                        for (JsonElement v : e.getValue().getAsJsonArray()) values.add(v.getAsString());
                        props.put(e.getKey(), values);
                    }
                }
                List<String> names = new ArrayList<>(props.keySet());
                int[] stride = new int[names.size()];
                int acc = 1;
                for (int i = names.size() - 1; i >= 0; i--) {
                    stride[i] = acc;
                    acc *= props.get(names.get(i)).size();
                }
                if (acc != states.size()) throw new IllegalStateException(name + ": property product " + acc + " != " + states.size());
                for (var s : states.entrySet()) {
                    JsonObject so = s.getValue().getAsJsonObject();
                    int sid = so.get("stateId").getAsInt();
                    // Verify the linear layout: stateId = first + Σ valueIndex × stride.
                    int expect = first;
                    String key = s.getKey();
                    String inner = key.substring(1, key.length() - 1);
                    if (!inner.isEmpty()) {
                        for (String kv : inner.split(",")) {
                            String[] parts = kv.split("=", 2);
                            int pi = names.indexOf(parts[0]);
                            expect += props.get(parts[0]).indexOf(parts[1]) * stride[pi];
                        }
                    }
                    if (expect != sid) throw new IllegalStateException(name + key + ": state " + sid + " != layout " + expect);
                    stBlock[sid] = id;
                    stFlags[sid] = flags(so, b);
                    stEmission[sid] = integer(so, "lightEmission", integer(b, "lightEmission", 0));
                    stOpacity[sid] = integer(so, "lightBlock", integer(b, "lightBlock", 0));
                    stCollision[sid] = shape(str(so, "collisionShape", str(b, "collisionShape", "[]")));
                    stOutline[sid] = shape(str(so, "shape", str(b, "shape", "[]")));
                    stOcclusion[sid] = shape(str(so, "occlusionShape", str(b, "occlusionShape", "[]")));
                    stInteraction[sid] = shape(str(so, "interactionShape", str(b, "interactionShape", "[]")));
                }
                d.writeUTF(name);
                d.writeInt(first);
                d.writeInt(states.size());
                d.writeInt(b.get("defaultStateId").getAsInt());
                d.writeFloat(flt(b, "hardness", 0f));
                d.writeFloat(flt(b, "explosionResistance", 0f));
                d.writeFloat(flt(b, "friction", 0.6f));
                d.writeFloat(flt(b, "speedFactor", 1f));
                d.writeFloat(flt(b, "jumpFactor", 1f));
                d.writeInt(itemIds.getOrDefault(str(b, "correspondingItem", ""), -1));
                d.writeByte(pushReaction(str(b, "pushReaction", "NORMAL")));
                JsonElement be = b.get("blockEntity");
                d.writeInt(be == null ? -1 : be.getAsJsonObject().get("id").getAsInt());
                d.writeUTF(str(b, "soundType", ""));
                d.writeByte(names.size());
                for (String n : names) {
                    d.writeUTF(n);
                    List<String> values = props.get(n);
                    d.writeByte(values.size());
                    for (String v : values) d.writeUTF(v);
                }
            }

            // ---- states ----
            d.writeInt(stateCount);
            for (int s = 0; s < stateCount; s++) {
                d.writeShort(stBlock[s]);
                d.writeInt(stFlags[s]);
                d.writeByte(stEmission[s]);
                d.writeByte(stOpacity[s]);
                d.writeShort(stCollision[s]);
                d.writeShort(stOutline[s]);
                d.writeShort(stOcclusion[s]);
                d.writeShort(stInteraction[s]);
            }

            // ---- shapes (vanilla AABBs in block-local coordinates, doubles) ----
            d.writeInt(shapes.size());
            for (double[] sh : shapes) {
                d.writeShort(sh.length / 6);
                for (double v : sh) d.writeDouble(v);
            }

            // ---- items ----
            d.writeInt(items.size());
            for (var e : items) {
                JsonObject o = e.getValue().getAsJsonObject();
                JsonObject c = o.getAsJsonObject("components");
                d.writeUTF(e.getKey());
                d.writeByte(integer(c, "minecraft:max_stack_size", 64));
                d.writeInt(integer(c, "minecraft:max_damage", 0));
                d.writeInt(blockIds.getOrDefault(str(o, "correspondingBlock", ""), -1));
            }

            // ---- attributes ----
            d.writeInt(attributes.size());
            for (var e : attributes) {
                JsonObject o = e.getValue().getAsJsonObject();
                d.writeUTF(e.getKey());
                d.writeDouble(o.get("defaultValue").getAsDouble());
                d.writeDouble(o.get("minValue").getAsDouble());
                d.writeDouble(o.get("maxValue").getAsDouble());
                d.writeBoolean(bool(o, "clientSync", false));
            }

            // ---- entity types ----
            d.writeInt(entities.size());
            for (var e : entities) {
                JsonObject o = e.getValue().getAsJsonObject();
                d.writeUTF(e.getKey());
                d.writeUTF(str(o, "packetType", ""));
                d.writeDouble(o.get("width").getAsDouble());
                d.writeDouble(o.get("height").getAsDouble());
                d.writeDouble(o.get("eyeHeight").getAsDouble());
                d.writeInt(integer(o, "clientTrackingRange", 5));
                JsonObject attrs = o.getAsJsonObject("defaultAttributes");
                d.writeShort(attrs == null ? 0 : attrs.size());
                if (attrs != null) {
                    for (var a : attrs.entrySet()) {
                        Integer aid = attrIds.get(a.getKey());
                        if (aid == null) throw new IllegalStateException("unknown attribute " + a.getKey());
                        d.writeShort(aid);
                        d.writeDouble(a.getValue().getAsDouble());
                    }
                }
            }

            writeNames(d, blockEntities);
            writeNames(d, sounds);
            writeNames(d, particles);
            writeNames(d, fluids);
        }

        Path java = out.resolve("java/dev/mulcor/registry");
        Files.createDirectories(java);
        constants(java, "BlockId", "Vanilla block type ids (not state ids; see {@code BlockData.defaultState}).", blocks);
        constants(java, "ItemId", "Vanilla item ids.", items);
        constants(java, "EntityTypeId", "Vanilla entity type ids.", entities);
        constants(java, "AttributeId", "Vanilla attribute ids.", attributes);
        constants(java, "BlockEntityTypeId", "Vanilla block entity type ids.", blockEntities);
        constants(java, "SoundId", "Vanilla sound event ids.", sounds);
        constants(java, "ParticleId", "Vanilla particle type ids.", particles);
        constants(java, "FluidId", "Vanilla fluid ids.", fluids);
    }

    private static void writeNames(DataOutputStream d, List<Map.Entry<String, JsonElement>> list) throws IOException {
        d.writeInt(list.size());
        for (var e : list) d.writeUTF(e.getKey());
    }

    private static void constants(Path dir, String cls, String doc, List<Map.Entry<String, JsonElement>> list) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("package dev.mulcor.registry;\n\n/** ").append(doc).append(" Generated from vanilla data; do not edit. */\n");
        sb.append("public final class ").append(cls).append(" {\n");
        for (int i = 0; i < list.size(); i++) {
            String key = list.get(i).getKey();
            String n = key.substring(key.indexOf(':') + 1).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
            if (Character.isDigit(n.charAt(0))) n = "_" + n;
            sb.append("    /** {@code ").append(key).append("} */\n    public static final int ").append(n).append(" = ").append(i).append(";\n");
        }
        sb.append("    public static final int COUNT = ").append(list.size()).append(";\n\n    private ").append(cls).append("() {}\n}\n");
        Files.writeString(dir.resolve(cls + ".java"), sb.toString());
    }
}
