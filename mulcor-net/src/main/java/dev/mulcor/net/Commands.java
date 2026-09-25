package dev.mulcor.net;

import dev.mulcor.core.World;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.command.ArgumentParserType;
import net.minestom.server.network.packet.server.play.DeclareCommandsPacket;

/**
 * Vanilla commands a player can run (cold: parsed on the player's event loop when a command packet arrives):
 * {@code /gamemode}, {@code /time}, {@code /tp} ({@code /teleport}), {@code /say}, {@code /me}, {@code /msg}
 * ({@code /tell}, {@code /w}), {@code /list} and {@code /help}. Replies use vanilla's translation keys, so the client
 * renders them in its own language exactly as a vanilla server's. Every player may run every command (like a world
 * with cheats on); entity selectors are {@code @s}, {@code @p}, {@code @a}, {@code @r} and player names, without
 * {@code [...]} arguments.
 */
final class Commands {
    private Commands() {}

    private static final String[] MODES = {"survival", "creative", "adventure", "spectator"};

    // ---- the command tree the client completes and validates against ----------------------------------------------

    private static final class Tree {
        final List<DeclareCommandsPacket.Node> nodes = new ArrayList<>();

        int add(int type, boolean executable, String name, ArgumentParserType parser, byte[] properties, String suggestions,
                int... children) {
            var n = new DeclareCommandsPacket.Node();
            n.flags = (byte) (type | (executable ? 0x04 : 0) | (suggestions != null ? 0x10 : 0));
            n.children = children;
            n.name = name;
            n.parser = parser;
            n.properties = properties;
            n.suggestionsType = suggestions;
            nodes.add(n);
            return nodes.size() - 1;
        }

        int literal(String name, boolean executable, int... children) {
            return add(1, executable, name, null, null, null, children);
        }

        int argument(String name, ArgumentParserType parser, byte[] properties, boolean executable, int... children) {
            return add(2, executable, name, parser, properties, null, children);
        }
    }

    /** Entity argument flags: single entity 0x01, players only 0x02. */
    private static final byte[] PLAYERS = {0x02}, PLAYER = {0x03}, ENTITY = {0x01};

    static final DeclareCommandsPacket TREE = buildTree();

    private static DeclareCommandsPacket buildTree() {
        Tree t = new Tree();
        List<Integer> top = new ArrayList<>();
        // gamemode <gamemode> [<target>]
        int gmTarget = t.argument("target", ArgumentParserType.ENTITY, PLAYERS, true);
        int gmMode = t.argument("gamemode", ArgumentParserType.GAMEMODE, null, true, gmTarget);
        top.add(t.literal("gamemode", false, gmMode));
        // time set|add <time>, time set day|noon|night|midnight, time query daytime|gametime|day
        byte[] minZero = {0, 0, 0, 0};
        int setTime = t.argument("time", ArgumentParserType.TIME, minZero, true);
        int set = t.literal("set", false, t.literal("day", true), t.literal("noon", true), t.literal("night", true),
                t.literal("midnight", true), setTime);
        int add = t.literal("add", false, t.argument("time", ArgumentParserType.TIME, minZero, true));
        int query = t.literal("query", false, t.literal("daytime", true), t.literal("gametime", true), t.literal("day", true));
        top.add(t.literal("time", false, set, add, query));
        // tp|teleport <location> | <destination> | <targets> <location> | <targets> <destination>
        int location = t.argument("location", ArgumentParserType.VEC3, null, true);
        int destination = t.argument("destination", ArgumentParserType.ENTITY, ENTITY, true);
        int targets = t.argument("targets", ArgumentParserType.ENTITY, new byte[] {0},
                false, t.argument("location", ArgumentParserType.VEC3, null, true),
                t.argument("destination", ArgumentParserType.ENTITY, ENTITY, true));
        top.add(t.literal("teleport", false, location, destination, targets));
        top.add(t.literal("tp", false, location, destination, targets));
        // say|me <message>, msg|tell|w <targets> <message>
        top.add(t.literal("say", false, t.argument("message", ArgumentParserType.MESSAGE, null, true)));
        top.add(t.literal("me", false, t.argument("action", ArgumentParserType.MESSAGE, null, true)));
        int msgTargets = t.argument("targets", ArgumentParserType.ENTITY, PLAYERS, false,
                t.argument("message", ArgumentParserType.MESSAGE, null, true));
        for (String alias : new String[] {"msg", "tell", "w"}) top.add(t.literal(alias, false, msgTargets));
        top.add(t.literal("list", true));
        top.add(t.literal("help", true));
        int root = t.add(0, false, null, null, null, null, top.stream().mapToInt(Integer::intValue).toArray());
        return new DeclareCommandsPacket(t.nodes, root);
    }

    // ---- execution ------------------------------------------------------------------------------------------------

    /** A command failed: the reason goes back to the player in red. */
    private static final class Failure extends RuntimeException {
        final Component message;

        Failure(Component message) {
            super(null, null, false, false);
            this.message = message;
        }
    }

    private static Failure fail(String key, Component... args) {
        return new Failure(Component.translatable(key, List.of(args)));
    }

    /** Run {@code line} (without the leading slash) for {@code source}, on its event loop. */
    static void execute(PlaySession source, String line) {
        String[] w = line.trim().split(" +");
        try {
            switch (w[0].toLowerCase(Locale.ROOT)) {
                case "gamemode" -> gamemode(source, w);
                case "time" -> time(source, w);
                case "tp", "teleport" -> teleport(source, w);
                case "say" -> {
                    need(w, 2);
                    broadcast(source.players(), Component.translatable("chat.type.announcement",
                            Component.text(source.name()), Component.text(rest(line, 1))));
                }
                case "me" -> {
                    need(w, 2);
                    broadcast(source.players(), Component.translatable("chat.type.emote",
                            Component.text(source.name()), Component.text(rest(line, 1))));
                }
                case "msg", "tell", "w" -> {
                    need(w, 3);
                    String text = rest(line, 2);
                    for (PlaySession p : targets(source, w[1])) {
                        p.post(Component.translatable("commands.message.display.incoming", Component.text(source.name()),
                                Component.text(text)).color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));
                        source.system(Component.translatable("commands.message.display.outgoing", Component.text(p.name()),
                                Component.text(text)).color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));
                    }
                }
                case "list" -> {
                    List<String> names = new ArrayList<>();
                    for (PlaySession p : all(source.players())) names.add(p.name());
                    source.system(Component.translatable("commands.list.players", Component.text(names.size()),
                            Component.text(source.maxPlayers()), Component.text(String.join(", ", names))));
                }
                case "help" -> {
                    for (String usage : new String[] {"/gamemode <gamemode> [<target>]",
                            "/time (add|query|set) ...", "/teleport (<location>|<destination>|<targets>) ...",
                            "/say <message>", "/me <action>", "/msg <targets> <message>", "/list", "/help"}) {
                        source.system(Component.text(usage));
                    }
                }
                default -> throw fail("command.unknown.command");
            }
        } catch (Failure f) {
            source.system(f.message.colorIfAbsent(NamedTextColor.RED));
        }
    }

    private static void need(String[] w, int n) {
        if (w.length < n) throw fail("command.unknown.argument");
    }

    /** The text after the first {@code words} words of {@code line}. */
    private static String rest(String line, int words) {
        String s = line.trim();
        for (int i = 0; i < words; i++) s = s.substring(s.indexOf(' ') + 1).stripLeading();
        return s;
    }

    private static void gamemode(PlaySession source, String[] w) {
        need(w, 2);
        int mode = List.of(MODES).indexOf(w[1].toLowerCase(Locale.ROOT));
        if (mode < 0) throw fail("argument.gamemode.invalid", Component.text(w[1]));
        List<PlaySession> targets = w.length > 2 ? targets(source, w[2]) : List.of(source);
        Component name = Component.translatable("gameMode." + MODES[mode]);
        for (PlaySession p : targets) {
            if (p.gameMode() == mode) continue; // vanilla: no change, no feedback
            p.run(() -> p.changeGameMode(mode));
            if (p == source) {
                source.system(Component.translatable("commands.gamemode.success.self", name));
            } else {
                source.system(Component.translatable("commands.gamemode.success.other", Component.text(p.name()), name));
                p.post(Component.translatable("gameMode.changed", name));
            }
        }
    }

    private static void time(PlaySession source, String[] w) {
        need(w, 2);
        World world = source.world();
        long gameTime = source.gameTime(), dayTime = gameTime + world.dayTimeOffset;
        switch (w[1]) {
            case "set" -> {
                need(w, 3);
                long t = switch (w[2]) {
                    case "day" -> 1000;
                    case "noon" -> 6000;
                    case "night" -> 13000;
                    case "midnight" -> 18000;
                    default -> ticks(w[2]);
                };
                world.dayTimeOffset = t - gameTime;
                source.system(Component.translatable("commands.time.set", Component.text(t)));
            }
            case "add" -> {
                need(w, 3);
                long t = ticks(w[2]);
                world.dayTimeOffset += t;
                source.system(Component.translatable("commands.time.set", Component.text((dayTime + t) % 24000)));
            }
            case "query" -> {
                need(w, 3);
                long v = switch (w[2]) {
                    case "daytime" -> Math.floorMod(dayTime, 24000L);
                    case "gametime" -> gameTime % Integer.MAX_VALUE;
                    case "day" -> Math.floorDiv(dayTime, 24000L) % Integer.MAX_VALUE;
                    default -> throw fail("command.unknown.argument");
                };
                source.system(Component.translatable("commands.time.query", Component.text(v)));
                return;
            }
            default -> throw fail("command.unknown.argument");
        }
        for (PlaySession p : all(source.players())) p.run(p::resendTime);
    }

    /** {@code TimeArgument}: a number with an optional unit (d = 24000 ticks, s = 20, t = 1). */
    private static long ticks(String s) {
        try {
            float scale = 1;
            if (s.endsWith("d")) scale = 24000;
            else if (s.endsWith("s")) scale = 20;
            String num = s.endsWith("d") || s.endsWith("s") || s.endsWith("t") ? s.substring(0, s.length() - 1) : s;
            long t = Math.round(Float.parseFloat(num) * scale);
            if (t < 0) throw fail("argument.time.tick_count_too_low", Component.text(0), Component.text(t));
            return t;
        } catch (NumberFormatException e) {
            throw fail("argument.time.invalid_unit");
        }
    }

    private static void teleport(PlaySession source, String[] w) {
        need(w, 2);
        List<PlaySession> movers;
        int at;
        if (w.length == 2 || w.length == 4) {
            movers = List.of(source);
            at = 1;
        } else {
            movers = targets(source, w[1]);
            at = 2;
        }
        double x, y, z;
        String destinationName = null;
        if (w.length - at >= 3) {
            x = coordinate(w[at], source.x(), true);
            y = coordinate(w[at + 1], source.y(), false);
            z = coordinate(w[at + 2], source.z(), true);
        } else {
            List<PlaySession> d = targets(source, w[at]);
            if (d.size() != 1) throw fail("argument.entity.toomany");
            PlaySession to = d.get(0);
            x = to.x();
            y = to.y();
            z = to.z();
            destinationName = to.name();
        }
        for (PlaySession p : movers) {
            final double fx = x, fy = y, fz = z;
            p.run(() -> p.teleport(fx, fy, fz));
        }
        Component who = movers.size() == 1 ? Component.text(movers.get(0).name()) : Component.text(movers.size());
        String n = movers.size() == 1 ? "single" : "multiple";
        if (destinationName != null) {
            source.system(Component.translatable("commands.teleport.success.entity." + n, who, Component.text(destinationName)));
        } else {
            source.system(Component.translatable("commands.teleport.success.location." + n, who, Component.text(format(x)),
                    Component.text(format(y)), Component.text(format(z))));
        }
    }

    private static String format(double d) {
        return String.format(Locale.ROOT, "%f", d);
    }

    /** {@code WorldCoordinate}: {@code ~}-relative or absolute; whole x/z numbers are block centres (+0.5). */
    private static double coordinate(String s, double current, boolean centre) {
        try {
            if (s.startsWith("~")) return current + (s.length() == 1 ? 0 : Double.parseDouble(s.substring(1)));
            if (s.startsWith("^")) throw fail("argument.pos.mixed");
            double v = Double.parseDouble(s);
            return centre && !s.contains(".") ? v + 0.5 : v;
        } catch (NumberFormatException e) {
            throw fail("argument.pos3d.incomplete");
        }
    }

    // ---- players --------------------------------------------------------------------------------------------------

    static List<PlaySession> all(Players players) {
        List<PlaySession> out = new ArrayList<>();
        for (int e = 0; e < players.capacity(); e++) {
            PlaySession p = players.get(e);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** An entity selector or player name: the players it names, at least one. */
    private static List<PlaySession> targets(PlaySession source, String s) {
        List<PlaySession> all = all(source.players());
        List<PlaySession> out = switch (s) {
            case "@s", "@p" -> List.of(source);
            case "@a", "@e" -> all;
            case "@r" -> all.isEmpty() ? all : List.of(all.get(ThreadLocalRandom.current().nextInt(all.size())));
            default -> {
                if (s.startsWith("@")) throw fail("argument.entity.selector.unknown", Component.text(s));
                List<PlaySession> named = new ArrayList<>();
                for (PlaySession p : all) if (p.name().equalsIgnoreCase(s)) named.add(p);
                yield named;
            }
        };
        if (out.isEmpty()) throw fail("argument.entity.notfound.player");
        return out;
    }

    static void broadcast(Players players, Component message) {
        System.out.println(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(message));
        for (PlaySession p : all(players)) p.post(message);
    }
}
