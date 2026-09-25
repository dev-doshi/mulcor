package dev.mulcor.core.region;

import dev.mulcor.registry.BlockData;
import dev.mulcor.registry.Items;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Block loot ({@code BlockLootSubProvider}, {@code Block.dropResources}) and harvest rules
 * ({@code Player.hasCorrectToolForDrops}). Loot tables are hand-ported per block family: drop the block's item, a
 * fixed item and count (ores, clay, glowstone), chance drops (leaves, gravel, grass), crops by age, the lower half
 * of two-block blocks, slabs, candles and pickles by count, and silk-touch-only blocks (glass, ice) which drop
 * nothing. Enchantments are not modelled: fortune never applies and silk touch never matches; shears do.
 *
 * <p>The rules are compiled once into per-block ints; dropping allocates nothing.
 */
final class Loot {
    // rule kinds (low 5 bits); ITEM and CROP carry an item in bits 16-31, ITEM its count range in bits 5-15
    private static final int SELF = 0, NONE = 1, ITEM = 2, LEAVES = 3, GRAVEL = 4, GRASS = 5, TALL_GRASS = 6,
            CROP = 7, PROP_EQUALS = 8, SLAB = 9, COUNT = 10, POTTED = 11, SHEARS_ONLY = 12, NETHER_WART = 14,
            COCOA = 15, BERRIES = 16, MUSHROOM_BLOCK = 17, DEAD_BUSH = 18, SNOW = 19, GILDED = 20, SEAGRASS = 22;
    private static final int BLOCKS = dev.mulcor.registry.BlockId.COUNT;
    private static final int[] RULE = new int[BLOCKS];
    /** Per block: the property the rule reads (half, part, type, age, candles, pickles, layers), or -1. */
    private static final int[] PROP = new int[BLOCKS];
    /** Per block: the property value the rule wants (lower half, head part, double slab), or the max age. */
    private static final int[] VAL = new int[BLOCKS];
    /** Per block: a second item (seeds, the potted plant, the sapling | chance << 16 | apples << 24). */
    private static final int[] AUX = new int[BLOCKS];
    /** Harvest: per block, the bit set of correct tools (null: any tool drops). */
    private static final long[][] HARVEST = new long[BLOCKS][];
    private static final int WORDS = (dev.mulcor.registry.ItemId.COUNT + 63) >>> 6;
    private static final int SHEARS = item("shears"), STICK = item("stick"), APPLE = item("apple");
    private static final int WHEAT_SEEDS = item("wheat_seeds"), FLINT = item("flint"), POISONOUS = item("poisonous_potato");
    private static final int GOLD_NUGGET = item("gold_nugget"), SNOWBALL = item("snowball"), POTATO = item("potato");
    private static final int FLOWER_POT = item("flower_pot"), SEAGRASS_ITEM = item("seagrass");

    static {
        for (int b = 0; b < BLOCKS; b++) {
            PROP[b] = -1;
            RULE[b] = rule(b);
        }
        try (var in = Loot.class.getResourceAsStream("/dev/mulcor/core/harvest.txt")) {
            if (in == null) throw new IllegalStateException("harvest.txt missing");
            var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int sp = line.indexOf(' ');
                int b = BlockData.blockByName(line.substring(0, sp));
                if (b < 0) continue;
                long[] bits = new long[WORDS];
                for (String t : line.substring(sp + 1).split("\\|")) {
                    int i = item(t);
                    if (i > 0) bits[i >>> 6] |= 1L << i;
                }
                HARVEST[b] = bits;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int item(String name) {
        return Items.byName("minecraft:" + name);
    }

    private static int fixed(int item, int min, int max) {
        return item <= 0 ? NONE : ITEM | min << 5 | (max - min) << 10 | item << 16;
    }

    /** Rule {@code kind} reading property {@code prop} of block {@code b}, wanting {@code value} (null: its max). */
    private static int withProp(int b, int kind, String prop, String value) {
        int p = BlockData.property(b, prop);
        if (p < 0) return kind == PROP_EQUALS ? SELF : kind;
        PROP[b] = p;
        VAL[b] = value == null ? BlockData.valueCount(p) - 1 : BlockData.valueIndex(p, value);
        return kind;
    }

    private static int crop(int b, String product, String seed) {
        AUX[b] = item(seed);
        withProp(b, CROP, "age", null);
        return CROP | item(product) << 16;
    }

    private static int rule(int b) {
        String n = BlockData.name(b);
        n = n.substring(n.indexOf(':') + 1);
        int self = BlockData.item(b);
        switch (n) {
            case "stone": return fixed(item("cobblestone"), 1, 1);
            case "deepslate": return fixed(item("cobbled_deepslate"), 1, 1);
            case "grass_block", "mycelium", "podzol", "dirt_path", "farmland": return fixed(item("dirt"), 1, 1);
            case "gravel": return GRAVEL;
            case "clay": return fixed(item("clay_ball"), 4, 4);
            case "glowstone": return fixed(item("glowstone_dust"), 2, 4);
            case "sea_lantern": return fixed(item("prismarine_crystals"), 2, 3);
            case "melon": return fixed(item("melon_slice"), 3, 7);
            case "bookshelf": return fixed(item("book"), 3, 3);
            case "snow_block": return fixed(SNOWBALL, 4, 4);
            case "snow": return withProp(b, SNOW, "layers", null);
            case "coal_ore", "deepslate_coal_ore": return fixed(item("coal"), 1, 1);
            case "iron_ore", "deepslate_iron_ore": return fixed(item("raw_iron"), 1, 1);
            case "gold_ore", "deepslate_gold_ore": return fixed(item("raw_gold"), 1, 1);
            case "copper_ore", "deepslate_copper_ore": return fixed(item("raw_copper"), 2, 5);
            case "diamond_ore", "deepslate_diamond_ore": return fixed(item("diamond"), 1, 1);
            case "emerald_ore", "deepslate_emerald_ore": return fixed(item("emerald"), 1, 1);
            case "lapis_ore", "deepslate_lapis_ore": return fixed(item("lapis_lazuli"), 4, 9);
            case "redstone_ore", "deepslate_redstone_ore": return fixed(item("redstone"), 4, 5);
            case "nether_quartz_ore": return fixed(item("quartz"), 1, 1);
            case "nether_gold_ore": return fixed(GOLD_NUGGET, 2, 6);
            case "gilded_blackstone": return GILDED;
            case "amethyst_cluster": return fixed(item("amethyst_shard"), 4, 4);
            case "cobweb": return fixed(item("string"), 1, 1);
            case "chorus_plant": return fixed(item("chorus_fruit"), 0, 1);
            case "short_grass", "fern": return GRASS;
            case "tall_grass": AUX[b] = item("short_grass"); return withProp(b, TALL_GRASS, "half", "lower");
            case "large_fern": AUX[b] = item("fern"); return withProp(b, TALL_GRASS, "half", "lower");
            case "tall_seagrass": return SEAGRASS;
            case "dead_bush": return DEAD_BUSH;
            case "wheat": return crop(b, "wheat", "wheat_seeds");
            case "carrots": return crop(b, "carrot", "carrot");
            case "potatoes": return crop(b, "potato", "potato");
            case "beetroots": return crop(b, "beetroot", "beetroot_seeds");
            case "nether_wart": return withProp(b, NETHER_WART, "age", null);
            case "cocoa": return withProp(b, COCOA, "age", null);
            case "sweet_berry_bush": return withProp(b, BERRIES, "age", null);
            case "brown_mushroom_block": return MUSHROOM_BLOCK | item("brown_mushroom") << 16;
            case "red_mushroom_block": return MUSHROOM_BLOCK | item("red_mushroom") << 16;
            case "seagrass", "vine", "glow_lichen", "hanging_roots", "nether_sprouts", "pale_hanging_moss", "bush",
                    "short_dry_grass", "tall_dry_grass", "small_dripleaf": return SHEARS_ONLY;
            case "sea_pickle": return withProp(b, COUNT, "pickles", null);
            case "candle_cake": return fixed(item("candle"), 1, 1);
            case "glass", "glass_pane", "ice", "packed_ice", "blue_ice", "turtle_egg", "sniffer_egg", "budding_amethyst",
                    "spawner", "trial_spawner", "vault", "infested_stone", "infested_cobblestone", "infested_stone_bricks",
                    "infested_mossy_stone_bricks", "infested_cracked_stone_bricks", "infested_chiseled_stone_bricks",
                    "infested_deepslate", "cake", "frosted_ice", "bee_nest", "reinforced_deepslate", "small_amethyst_bud",
                    "medium_amethyst_bud", "large_amethyst_bud": return NONE;
            default: break;
        }
        if (n.endsWith("_stained_glass") || n.endsWith("_stained_glass_pane")) return NONE;
        if (n.endsWith("_candle_cake")) return fixed(item(n.substring(0, n.length() - "_cake".length())), 1, 1);
        if (n.endsWith("_candle") || n.equals("candle")) return withProp(b, COUNT, "candles", null);
        if (n.endsWith("_leaves")) {
            String wood = n.substring(0, n.length() - "_leaves".length());
            int sap = switch (wood) {
                case "azalea" -> item("azalea");
                case "flowering_azalea" -> item("flowering_azalea");
                case "mangrove" -> 0;
                default -> Math.max(0, item(wood + "_sapling"));
            };
            AUX[b] = sap | (wood.equals("jungle") ? 40 : 20) << 16 | (wood.equals("oak") || wood.equals("dark_oak") ? 1 << 24 : 0);
            return LEAVES;
        }
        if (n.startsWith("potted_")) {
            String plant = n.substring("potted_".length());
            if (plant.equals("azalea_bush")) plant = "azalea";
            if (plant.equals("flowering_azalea_bush")) plant = "flowering_azalea";
            AUX[b] = Math.max(0, item(plant));
            return POTTED;
        }
        if (n.endsWith("_slab")) return withProp(b, SLAB, "type", "double");
        if (n.endsWith("_bed")) return withProp(b, PROP_EQUALS, "part", "head");
        if (n.endsWith("_door") || n.equals("sunflower") || n.equals("lilac") || n.equals("rose_bush") || n.equals("peony")
                || n.equals("pitcher_plant")) {
            return withProp(b, PROP_EQUALS, "half", "lower");
        }
        return self <= 0 ? NONE : SELF; // air, fluids, fire, portals
    }

    private Loot() {}

    /**
     * {@code Player.hasCorrectToolForDrops}: blocks that require the correct tool drop nothing unless {@code tool}
     * is one of theirs.
     */
    static boolean canHarvest(int state, long tool) {
        long[] bits = HARVEST[BlockData.block(state)];
        if (bits == null) return true;
        int item = Stacks.item(tool);
        return tool != Stacks.EMPTY && (bits[item >>> 6] >>> item & 1) != 0;
    }

    /**
     * {@code Block.dropResources(state, level, pos, blockEntity, entity, tool)}: pop the block's loot at (x, y, z).
     * {@code tool} is the breaking player's main-hand stack, or empty (block updates, pistons).
     */
    static void dropResources(Region r, int x, int y, int z, int state, long tool) {
        int b = BlockData.block(state);
        int rule = RULE[b];
        boolean shears = tool != Stacks.EMPTY && Stacks.item(tool) == SHEARS;
        int self = BlockData.item(b);
        switch (rule & 31) {
            case SELF -> pop(r, x, y, z, self, 1);
            case NONE -> { }
            case ITEM -> {
                int min = rule >>> 5 & 31, span = rule >>> 10 & 63;
                pop(r, x, y, z, rule >>> 16, min + (span == 0 ? 0 : r.nextInt(span + 1)));
            }
            case GILDED -> {
                if (r.nextFloat() < 0.1F) pop(r, x, y, z, GOLD_NUGGET, 2 + r.nextInt(4));
                else pop(r, x, y, z, self, 1);
            }
            case GRAVEL -> pop(r, x, y, z, r.nextFloat() < 0.1F ? FLINT : self, 1);
            case LEAVES -> {
                if (shears) {
                    pop(r, x, y, z, self, 1);
                    return;
                }
                int sap = AUX[b];
                if ((sap & 0xFFFF) != 0 && r.nextFloat() < 1.0F / (sap >>> 16 & 0xFF)) pop(r, x, y, z, sap & 0xFFFF, 1);
                if (r.nextFloat() < 0.02F) pop(r, x, y, z, STICK, 1 + r.nextInt(2));
                if ((sap >>> 24) != 0 && r.nextFloat() < 0.005F) pop(r, x, y, z, APPLE, 1);
            }
            case GRASS -> {
                if (shears) pop(r, x, y, z, self, 1);
                else if (r.nextFloat() < 0.125F) pop(r, x, y, z, WHEAT_SEEDS, 1);
            }
            case TALL_GRASS -> {
                if (!propIs(b, state)) return;
                if (shears) pop(r, x, y, z, AUX[b], 2);
                else if (r.nextFloat() < 0.125F) pop(r, x, y, z, WHEAT_SEEDS, 1);
            }
            case SEAGRASS -> {
                if (shears) pop(r, x, y, z, SEAGRASS_ITEM, 2);
            }
            case SHEARS_ONLY -> {
                if (shears) pop(r, x, y, z, self, 1);
            }
            case DEAD_BUSH -> {
                if (shears) pop(r, x, y, z, self, 1);
                else pop(r, x, y, z, STICK, r.nextInt(3));
            }
            case SNOW -> {
                // requires a shovel (harvest table): snowballs by layers
                pop(r, x, y, z, SNOWBALL, PROP[b] < 0 ? 1 : BlockData.intValue(state, PROP[b]));
            }
            case CROP -> {
                boolean mature = PROP[b] >= 0 && BlockData.get(state, PROP[b]) >= VAL[b];
                int product = rule >>> 16, seed = AUX[b];
                if (product == seed) { // carrots, potatoes: the product is the seed
                    pop(r, x, y, z, product, 1 + (mature ? binomial(r, 3, 0.5714286F) : 0));
                    if (mature && seed == POTATO && r.nextFloat() < 0.02F) pop(r, x, y, z, POISONOUS, 1);
                } else {
                    pop(r, x, y, z, mature ? product : seed, 1);
                    if (mature) pop(r, x, y, z, seed, 1 + binomial(r, 3, 0.5714286F));
                }
            }
            case NETHER_WART -> pop(r, x, y, z, self, ageOf(b, state) >= 3 ? 2 + r.nextInt(3) : 1);
            case COCOA -> pop(r, x, y, z, self, ageOf(b, state) >= 2 ? 3 : 1);
            case BERRIES -> {
                int age = ageOf(b, state);
                if (age == 3) pop(r, x, y, z, self, 2 + r.nextInt(2));
                else if (age == 2) pop(r, x, y, z, self, 1 + r.nextInt(2));
            }
            case MUSHROOM_BLOCK -> pop(r, x, y, z, rule >>> 16, Math.max(0, r.nextInt(9) - 6));
            case PROP_EQUALS -> {
                if (propIs(b, state)) pop(r, x, y, z, self, 1);
            }
            case SLAB -> pop(r, x, y, z, self, propIs(b, state) ? 2 : 1);
            case COUNT -> pop(r, x, y, z, self, PROP[b] < 0 ? 1 : BlockData.intValue(state, PROP[b]));
            case POTTED -> {
                pop(r, x, y, z, FLOWER_POT, 1);
                pop(r, x, y, z, AUX[b], 1);
            }
            default -> pop(r, x, y, z, self, 1);
        }
    }

    private static boolean propIs(int b, int state) {
        return PROP[b] >= 0 && BlockData.get(state, PROP[b]) == VAL[b];
    }

    private static int ageOf(int b, int state) {
        return PROP[b] < 0 ? 0 : BlockData.get(state, PROP[b]);
    }

    /** {@code BinomialDistributionGenerator(n, p)}. */
    private static int binomial(Region r, int n, float p) {
        int k = 0;
        for (int i = 0; i < n; i++) if (r.nextFloat() < p) k++;
        return k;
    }

    private static void pop(Region r, int x, int y, int z, int item, int count) {
        if (item <= 0 || count <= 0) return;
        int max = Stacks.maxStackSize(Stacks.of(item, 1));
        while (count > 0) { // a loot stack over the max size is split (LootTable.createStackSplitter)
            int n = Math.min(count, max);
            ItemEntities.popResource(r, x, y, z, Stacks.of(item, n));
            count -= n;
        }
    }
}
