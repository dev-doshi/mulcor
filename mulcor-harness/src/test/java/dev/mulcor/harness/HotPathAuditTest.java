package dev.mulcor.harness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Static zero-allocation audit of the hot path. Starting from the tick and network entry points, it walks the call
 * graph through every Mulcor method (virtual and interface calls resolve to every project implementation) and
 * flags heap allocation: {@code new}, array creation, {@code invokedynamic} (lambdas, string concatenation) and
 * boxing. The dynamic gates ({@code *ZeroAllocationTest}) measure what actually ran; this audit also covers
 * branches the scenarios never take.
 *
 * <p>Allowed: constructing a {@code Throwable} and computing its arguments (error paths), and methods listed in
 * {@link #COLD} with a reason. JDK and library code is not followed; the dynamic gates cover it.
 */
class HotPathAuditTest {
    /** Entry points: owner (internal name) → method names. */
    static final Map<String, List<String>> ROOTS = Map.of(
            "dev/mulcor/core/region/Region", List.of("tick"),
            "dev/mulcor/core/Engine$TickWorker", List.of("compute"),
            "dev/mulcor/core/Engine$TickRoot", List.of("compute", "onCompletion"),
            "dev/mulcor/net/IngressDecoder", List.of("decode"),
            "dev/mulcor/net/IngressHandler", List.of("channelRead"),
            "dev/mulcor/net/CipherCodec", List.of("channelRead", "write"),
            "dev/mulcor/net/PlayWriter", List.of("chunk", "keepAlive", "viewCenter", "batchStart", "batchFinished",
                    "acknowledgeBlockChange"));

    /** Methods deliberately excluded from the hot path, with the reason. Key: owner.name */
    static final Map<String, String> COLD = Map.of(
            "dev/mulcor/core/region/Region.describeRings", "diagnostics only",
            "dev/mulcor/net/CipherCodec.write#readOnly", "documented copy of read-only buffers; never produced by Mulcor");

    /** Methods the walk must reach (proves the call graph is followed, not just the roots). */
    static final List<String> MUST_VISIT = List.of("dev/mulcor/core/region/Sim.simulate",
            "dev/mulcor/memory/OffHeapRing.offer", "dev/mulcor/memory/BlockStorage.set",
            "dev/mulcor/net/ChunkEncoder.encodeSection", "dev/mulcor/net/IngressDecoder.frame",
            "dev/mulcor/core/Engine.commit");

    private final Map<String, ClassModel> classes = new HashMap<>();
    private final Set<String> visited = new HashSet<>();

    @Test
    void hotPathHasNoHeapAllocation() throws IOException {
        for (Class<?> anchor : List.of(dev.mulcor.core.Engine.class, dev.mulcor.memory.OffHeapRing.class,
                dev.mulcor.net.NetServer.class, dev.mulcor.registry.Registry.class)) {
            load(anchor);
        }
        List<String> violations = audit();
        for (String must : MUST_VISIT) assertTrue(visited.contains(must), "audit never reached " + must + "; visited " + visited.size());
        System.out.printf("hot-path audit: %d methods reachable from %d roots%n", visited.size(), ROOTS.size());
        assertTrue(violations.isEmpty(), violations.size() + " allocation sites reachable from the hot path:\n  "
                + String.join("\n  ", violations));
    }

    /** Negative control: the audit must flag a lambda, a boxing call and a `new` reachable from a root. */
    @Test
    void auditDetectsAllocation() throws IOException {
        load(HotPathAuditTest.class);
        var v = walk(List.<String[]>of(new String[] {"dev/mulcor/harness/HotPathAuditTest$Canary", "root"}));
        assertTrue(v.stream().anyMatch(s -> s.contains("invokedynamic")), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.contains("valueOf")), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.contains("new java/util/ArrayList")), v.toString());
        assertTrue(v.stream().noneMatch(s -> s.contains("IllegalStateException")), "throw paths are allowed: " + v);
    }

    @SuppressWarnings("unused")
    static final class Canary {
        static Object root(int x) {
            if (x < 0) throw new IllegalStateException("negative " + x);
            return helper(x);
        }

        static Object helper(int x) {
            Runnable r = () -> { };
            r.run();
            List<Integer> l = new ArrayList<>();
            l.add(x); // boxing
            return l;
        }
    }

    private void load(Class<?> anchor) throws IOException {
        Path location = Path.of(URI.create(anchor.getProtectionDomain().getCodeSource().getLocation().toString()));
        if (Files.isDirectory(location)) {
            loadTree(location);
        } else {
            try (FileSystem jar = FileSystems.newFileSystem(location, Map.of())) {
                loadTree(jar.getPath("/"));
            }
        }
    }

    private void loadTree(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                ClassModel cm = ClassFile.of().parse(Files.readAllBytes(f));
                String name = cm.thisClass().asInternalName();
                if (name.startsWith("dev/mulcor/")) classes.put(name, cm);
            }
        }
    }

    private List<String> audit() {
        List<String[]> roots = new ArrayList<>();
        ROOTS.forEach((owner, names) -> {
            if (!classes.containsKey(owner)) throw new AssertionError("root class missing: " + owner);
            for (String n : names) roots.add(new String[] {owner, n});
        });
        return walk(roots);
    }

    private List<String> walk(List<String[]> roots) {
        List<String> violations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<String[]> queue = new ArrayDeque<>(); // {owner, name, desc or null, trail}
        for (String[] r : roots) {
            ClassModel cm = classes.get(r[0]);
            boolean found = false;
            for (MethodModel m : cm.methods()) {
                if (m.methodName().stringValue().equals(r[1])) {
                    queue.add(new String[] {r[0], r[1], m.methodType().stringValue(), r[0] + "." + r[1]});
                    found = true;
                }
            }
            if (!found) throw new AssertionError("root method missing: " + r[0] + "." + r[1]);
        }
        while (!queue.isEmpty()) {
            String[] q = queue.poll();
            String key = q[0] + "." + q[1] + q[2];
            if (!seen.add(key) || COLD.containsKey(q[0] + "." + q[1])) continue;
            MethodModel m = find(q[0], q[1], q[2]);
            if (m == null) continue;
            visited.add(q[0] + "." + q[1]);
            m.code().ifPresent(code -> scan(code, q, violations, queue));
        }
        violations.sort(null);
        return violations;
    }

    private MethodModel find(String owner, String name, String desc) {
        for (String c = owner; c != null; ) {
            ClassModel cm = classes.get(c);
            if (cm == null) return null;
            for (MethodModel m : cm.methods()) {
                if (m.methodName().stringValue().equals(name) && m.methodType().stringValue().equals(desc)) return m;
            }
            c = cm.superclass().map(s -> s.asInternalName()).orElse(null);
        }
        return null;
    }

    private boolean isSubtype(String cls, String of) {
        if (cls.equals(of)) return true;
        ClassModel cm = classes.get(cls);
        if (cm == null) return false;
        if (cm.superclass().isPresent() && isSubtype(cm.superclass().get().asInternalName(), of)) return true;
        for (var i : cm.interfaces()) if (isSubtype(i.asInternalName(), of)) return true;
        return false;
    }

    private boolean isThrowable(String cls) {
        ClassModel cm = classes.get(cls);
        if (cm != null) return cm.superclass().map(s -> isThrowable(s.asInternalName())).orElse(false);
        try {
            return Throwable.class.isAssignableFrom(Class.forName(cls.replace('/', '.'), false, getClass().getClassLoader()));
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static final Set<String> BOXES = Set.of("java/lang/Integer", "java/lang/Long", "java/lang/Double",
            "java/lang/Float", "java/lang/Short", "java/lang/Byte", "java/lang/Character", "java/lang/Boolean");

    private void scan(CodeModel code, String[] q, List<String> violations, ArrayDeque<String[]> queue) {
        String where = q[3];
        int throwableDepth = 0; // inside `new SomeThrowable(...)` argument evaluation: allocation there is an error path
        List<String> pendingThrowables = new ArrayList<>();
        for (CodeElement e : code) {
            if (e instanceof NewObjectInstruction n) {
                String cls = n.className().asInternalName();
                if (isThrowable(cls)) {
                    throwableDepth++;
                    pendingThrowables.add(cls);
                } else if (throwableDepth == 0) {
                    violations.add(where + ": new " + cls);
                }
            } else if (e instanceof InvokeInstruction inv) {
                String owner = inv.owner().asInternalName(), name = inv.name().stringValue();
                if (throwableDepth > 0 && name.equals("<init>") && pendingThrowables.contains(owner)) {
                    pendingThrowables.remove(owner);
                    throwableDepth--;
                    continue;
                }
                if (throwableDepth > 0) continue;
                if (BOXES.contains(owner) && name.equals("valueOf")) {
                    violations.add(where + ": boxing " + owner + ".valueOf");
                    continue;
                }
                if (!owner.startsWith("dev/mulcor/")) continue;
                String desc = inv.type().stringValue();
                String next = where.length() > 400 ? where : where + " → " + simple(owner) + "." + name;
                if (inv.opcode() == Opcode.INVOKEVIRTUAL || inv.opcode() == Opcode.INVOKEINTERFACE) {
                    for (String c : classes.keySet()) {
                        if (isSubtype(c, owner) && declares(c, name, desc)) queue.add(new String[] {c, name, desc, next});
                    }
                }
                queue.add(new String[] {owner, name, desc, next});
            } else if (throwableDepth == 0) {
                if (e instanceof NewPrimitiveArrayInstruction || e instanceof NewReferenceArrayInstruction
                        || e instanceof NewMultiArrayInstruction) {
                    violations.add(where + ": array allocation");
                } else if (e instanceof InvokeDynamicInstruction indy) {
                    violations.add(where + ": invokedynamic " + indy.name().stringValue());
                }
            }
        }
    }

    private boolean declares(String cls, String name, String desc) {
        ClassModel cm = classes.get(cls);
        for (MethodModel m : cm.methods()) {
            if (m.methodName().stringValue().equals(name) && m.methodType().stringValue().equals(desc)) return true;
        }
        return false;
    }

    private static String simple(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }
}
