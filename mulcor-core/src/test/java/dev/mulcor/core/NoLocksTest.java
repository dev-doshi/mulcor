package dev.mulcor.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mulcor.memory.OffHeapRing;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Bytecode audit: the engine and memory modules must contain no monitors (synchronized methods or blocks),
 * no {@code Object.wait/notify}, and no lock-based JDK concurrency classes. {@code LockSupport.park} is allowed:
 * the driver parks while waiting for an epoch, which is idling, not locking.
 */
class NoLocksTest {
    private static final List<String> BANNED_OWNERS = List.of(
            "java/util/concurrent/locks/ReentrantLock", "java/util/concurrent/locks/ReentrantReadWriteLock",
            "java/util/concurrent/locks/StampedLock", "java/util/concurrent/locks/Lock",
            "java/util/concurrent/locks/Condition", "java/util/concurrent/Semaphore",
            "java/util/concurrent/CountDownLatch", "java/util/concurrent/CyclicBarrier",
            "java/util/concurrent/Phaser", "java/util/concurrent/Exchanger",
            "java/util/concurrent/ArrayBlockingQueue", "java/util/concurrent/LinkedBlockingQueue",
            "java/util/concurrent/LinkedBlockingDeque", "java/util/concurrent/BlockingQueue",
            "java/util/concurrent/CopyOnWriteArrayList", "java/util/concurrent/CopyOnWriteArraySet",
            "java/util/concurrent/ConcurrentHashMap", "java/util/Hashtable", "java/util/Vector",
            "java/lang/StringBuffer");

    static List<String> scan(Class<?> anchor, String packagePath) throws IOException {
        Path location = Path.of(URI.create(anchor.getProtectionDomain().getCodeSource().getLocation().toString()));
        List<String> violations = new ArrayList<>();
        if (Files.isDirectory(location)) {
            scanTree(location.resolve(packagePath), violations);
        } else {
            try (FileSystem jar = FileSystems.newFileSystem(location, Map.of())) {
                scanTree(jar.getPath(packagePath), violations);
            }
        }
        return violations;
    }

    private static void scanTree(Path root, List<String> violations) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                ClassModel cm = ClassFile.of().parse(Files.readAllBytes(f));
                String cls = cm.thisClass().asInternalName();
                for (MethodModel m : cm.methods()) {
                    String where = cls + "." + m.methodName().stringValue();
                    if (m.flags().has(AccessFlag.SYNCHRONIZED)) violations.add(where + ": synchronized method");
                    m.findAttribute(java.lang.classfile.Attributes.code()).ifPresent(code -> inspect(code, where, violations));
                }
            }
        }
    }

    private static void inspect(CodeModel code, String where, List<String> violations) {
        code.forEach(e -> {
            if (e instanceof MonitorInstruction) {
                violations.add(where + ": synchronized block");
            } else if (e instanceof InvokeInstruction inv) {
                String owner = inv.owner().asInternalName(), name = inv.name().stringValue();
                if (owner.equals("java/lang/Object") && (name.equals("wait") || name.startsWith("notify"))) {
                    violations.add(where + ": Object." + name);
                }
                if (BANNED_OWNERS.contains(owner)) violations.add(where + ": uses " + owner + "." + name);
                if (owner.equals("java/util/Collections") && name.startsWith("synchronized")) {
                    violations.add(where + ": Collections." + name);
                }
            } else if (e instanceof NewObjectInstruction n && BANNED_OWNERS.contains(n.className().asInternalName())) {
                violations.add(where + ": new " + n.className().asInternalName());
            } else if (e instanceof java.lang.classfile.Instruction i && i.opcode() == Opcode.MONITORENTER) {
                violations.add(where + ": monitorenter");
            }
        });
    }

    @Test
    void engineAndMemoryModulesAreLockFree() throws IOException {
        List<String> violations = new ArrayList<>();
        violations.addAll(scan(Engine.class, "dev/mulcor/core"));
        violations.addAll(scan(OffHeapRing.class, "dev/mulcor/memory"));
        assertTrue(violations.isEmpty(), "lock usage found:\n  " + String.join("\n  ", violations));
    }

    /** Negative control: the scanner must notice a synchronized block, or a clean result proves nothing. */
    @Test
    void scannerDetectsMonitors() throws IOException {
        List<String> v = scan(NoLocksTest.class, "dev/mulcor/core");
        assertTrue(v.stream().anyMatch(s -> s.contains("Canary") && s.contains("synchronized")), v.toString());
    }

    @SuppressWarnings("unused")
    static final class Canary {
        private final Object lock = new Object();
        int value;

        void bump() {
            synchronized (lock) {
                value++;
            }
        }
    }
}
