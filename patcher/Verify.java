import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Offline checks for the volume bar build.
 *
 * 1. Bytecode verification (ASM SimpleVerifier, full type checking) of
 *    every method in every class we ship, against the real K0133 class
 *    hierarchy.
 * 2. Every method/field/class our code references must exist in K0133
 *    (or in our own jar) with the exact signature.
 * 3. The patched stock classes must be instruction-for-instruction
 *    identical to the originals apart from the intended hook.
 *
 * Usage: Verify <k0133.jar> <ourClassesDir> <patchedDir> <origDir>
 */
public class Verify implements Opcodes {

    static int failures = 0;
    static Map<String, ClassNode> universe = new HashMap<>();

    public static void main(String[] a) throws Exception {
        File k = new File(a[0]);
        File ours = new File(a[1]);
        File patched = new File(a[2]);
        File orig = new File(a[3]);

        // Universe = K0133 classes, overridden by ours + patched (boot classpath order).
        try (ZipFile z = new ZipFile(k)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                ZipEntry en = e.nextElement();
                if (!en.getName().endsWith(".class")) continue;
                universe.put(en.getName().replace(".class", ""), node(z.getInputStream(en)));
            }
        }
        List<ClassNode> shipped = new ArrayList<>();
        for (File d : new File[] {ours, patched}) {
            Files.walk(d.toPath()).filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                try {
                    ClassNode cn = node(Files.newInputStream(p));
                    universe.put(cn.name, cn);
                    shipped.add(cn);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }

        ClassLoader hierarchy = new ClassLoader(null) {
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                ClassNode cn = universe.get(name.replace('.', '/'));
                if (cn == null) throw new ClassNotFoundException(name);
                // Define a hollow copy (no code) just for type relationships.
                org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
                cw.visit(V1_2, cn.access & ~ACC_ENUM, cn.name, null, cn.superName,
                        cn.interfaces.toArray(new String[0]));
                cw.visitEnd();
                byte[] b = cw.toByteArray();
                return defineClass(name, b, 0, b.length);
            }
        };

        for (ClassNode cn : shipped) {
            if (cn.version != 46) fail(cn.name + ": class version " + cn.version + " (device needs 46)");
            verifyMethods(cn, hierarchy);
            checkReferences(cn);
        }

        compareWithOriginal(orig, patched, "de/audi/tghu/tone/app/SoundListener",
                "updateVolume", "(ISI)V", 6);
        compareWithOriginal(orig, patched, "de/audi/tghu/tone/app/ToneModelBank",
                "<init>", "()V", -1);
        compareWithOriginal(orig, patched, "de/audi/tghu/tone/app/MenuVolumeHandler",
                "changeAudibleVolume|keyTyped", "(I)V", 5);

        System.out.println(failures == 0 ? "VERIFY OK (" + shipped.size() + " classes)" : "VERIFY FAILED: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    static ClassNode node(InputStream in) throws IOException {
        ClassNode cn = new ClassNode();
        new ClassReader(in).accept(cn, 0);
        in.close();
        return cn;
    }

    static void fail(String s) {
        failures++;
        System.out.println("FAIL " + s);
    }

    static void verifyMethods(ClassNode cn, ClassLoader loader) {
        for (MethodNode m : cn.methods) {
            if ((m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) continue;
            SimpleVerifier v = new SimpleVerifier(Type.getObjectType(cn.name),
                    cn.superName == null ? null : Type.getObjectType(cn.superName),
                    toTypes(cn.interfaces), (cn.access & ACC_INTERFACE) != 0);
            v.setClassLoader(loader);
            try {
                new Analyzer<>(v).analyze(cn.name, m);
            } catch (AnalyzerException e) {
                fail(cn.name + "." + m.name + m.desc + ": " + e.getMessage());
            }
        }
    }

    static List<Type> toTypes(List<String> names) {
        List<Type> l = new ArrayList<>();
        for (String n : names) l.add(Type.getObjectType(n));
        return l;
    }

    static void checkReferences(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode n : m.instructions) {
                if (n instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) n;
                    if (mi.owner.startsWith("[")) continue;
                    // Rendering the pop-up again clears its container and drops
                    // the widgets' drawings: nothing may mark it dirty.
                    if (mi.owner.equals("de/audi/tghu/tone/hmi/evolution/VolumeBarPopin")
                            && (mi.name.equals("setCompositesDirty") || mi.name.equals("setCompositeDirty")
                                || mi.name.equals("setInvalid"))) {
                        fail(cn.name + "." + m.name + " marks the pop-up dirty (" + mi.name + ")");
                    }
                    if (findMethod(mi.owner, mi.name, mi.desc) == null) {
                        fail(cn.name + " -> missing method " + mi.owner + "." + mi.name + mi.desc);
                    }
                } else if (n instanceof FieldInsnNode) {
                    FieldInsnNode fi = (FieldInsnNode) n;
                    if (findField(fi.owner, fi.name, fi.desc) == null) {
                        fail(cn.name + " -> missing field " + fi.owner + "." + fi.name + ":" + fi.desc);
                    }
                } else if (n instanceof TypeInsnNode) {
                    String t = ((TypeInsnNode) n).desc;
                    if (!t.startsWith("[") && !universe.containsKey(t)) {
                        fail(cn.name + " -> missing class " + t);
                    }
                }
            }
        }
    }

    static MethodNode findMethod(String owner, String name, String desc) {
        Deque<String> todo = new ArrayDeque<>();
        todo.add(owner);
        while (!todo.isEmpty()) {
            ClassNode c = universe.get(todo.poll());
            if (c == null) continue;
            for (MethodNode m : c.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) return m;
            }
            if (c.superName != null) todo.add(c.superName);
            todo.addAll(c.interfaces);
        }
        return null;
    }

    static FieldNode findField(String owner, String name, String desc) {
        Deque<String> todo = new ArrayDeque<>();
        todo.add(owner);
        while (!todo.isEmpty()) {
            ClassNode c = universe.get(todo.poll());
            if (c == null) continue;
            for (FieldNode f : c.fields) {
                if (f.name.equals(name) && f.desc.equals(desc)) return f;
            }
            if (c.superName != null) todo.add(c.superName);
            todo.addAll(c.interfaces);
        }
        return null;
    }

    /**
     * Every method except the patched one must match exactly. The patched
     * method must equal the original after removing the hook:
     * hookLen > 0: that many instructions inserted at the start.
     * hookLen = -1: ToneModelBank (count operand + 5 instructions before RETURN).
     */
    static void compareWithOriginal(File origDir, File patchedDir, String cls,
                                    String pm, String pd, int hookLen) throws IOException {
        ClassNode o = node(new FileInputStream(new File(origDir, cls + ".class")));
        ClassNode p = node(new FileInputStream(new File(patchedDir, cls + ".class")));
        if (o.methods.size() != p.methods.size() || o.fields.size() != p.fields.size()) {
            fail(cls + ": member count changed");
            return;
        }
        int diffs = 0;
        for (int i = 0; i < o.methods.size(); i++) {
            MethodNode om = o.methods.get(i), mm = p.methods.get(i);
            List<String> a = norm(om), b = norm(mm);
            if (java.util.Arrays.asList(pm.split("\\|")).contains(mm.name) && mm.desc.equals(pd)) {
                b = norm(stripHook(mm, hookLen));
            }
            if (!a.equals(b)) {
                diffs++;
                fail(cls + "." + om.name + om.desc + " differs beyond the hook");
            }
        }
        System.out.println("compare " + cls + ": " + (diffs == 0 ? "only the intended change" : diffs + " unexpected diffs"));
    }

    /**
     * Removes the inserted hook (and its try/catch entry) from a copy of the
     * patched method, so what's left can be compared to the original.
     * The hook is a contiguous block starting with a label that is the
     * start of an inserted Throwable handler range.
     */
    static MethodNode stripHook(MethodNode src, int kind) {
        MethodNode m = new MethodNode(src.access, src.name, src.desc, src.signature, null);
        src.accept(m);
        TryCatchBlockNode hook = null;
        for (TryCatchBlockNode t : m.tryCatchBlocks) {
            if ("java/lang/Throwable".equals(t.type) && insideHook(t)) { hook = t; break; }
        }
        if (hook == null) throw new IllegalStateException("hook try/catch not found in " + m.name);
        m.tryCatchBlocks.remove(hook);
        // block = [hook.start .. label after handler], i.e. up to the GOTO target
        AbstractInsnNode n = hook.start;
        JumpInsnNode jump = null;
        for (AbstractInsnNode x = hook.end; x != null; x = x.getNext()) {
            if (x.getOpcode() == GOTO) { jump = (JumpInsnNode) x; break; }
        }
        LabelNode after = jump.label;
        List<AbstractInsnNode> kill = new ArrayList<>();
        for (AbstractInsnNode x = n; x != null; x = x.getNext()) {
            kill.add(x);
            if (x == after) break;
        }
        for (AbstractInsnNode x : kill) m.instructions.remove(x);
        if (kind < 0) {
            for (AbstractInsnNode x : m.instructions) {
                if (x instanceof IntInsnNode && x.getOpcode() == BIPUSH && ((IntInsnNode) x).operand == 27) {
                    ((IntInsnNode) x).operand = 26;
                    break;
                }
            }
        }
        return m;
    }

    static boolean insideHook(TryCatchBlockNode t) {
        for (AbstractInsnNode x = t.start; x != null && x != t.end; x = x.getNext()) {
            if (x instanceof MethodInsnNode && ((MethodInsnNode) x).owner.equals("de/audi/tghu/tone/app/VolumeBar")) return true;
        }
        return false;
    }

    static List<String> norm(MethodNode m) {
        Map<LabelNode, Integer> labels = new HashMap<>();
        int li = 0;
        for (AbstractInsnNode n : m.instructions) if (n instanceof LabelNode) labels.put((LabelNode) n, li++);
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode n : m.instructions) {
            if (n.getOpcode() < 0) continue; // labels, line numbers, frames
            String s;
            if (n instanceof MethodInsnNode) {
                MethodInsnNode x = (MethodInsnNode) n; s = "M " + x.getOpcode() + " " + x.owner + "." + x.name + x.desc;
            } else if (n instanceof FieldInsnNode) {
                FieldInsnNode x = (FieldInsnNode) n; s = "F " + x.getOpcode() + " " + x.owner + "." + x.name + x.desc;
            } else if (n instanceof LdcInsnNode) {
                s = "LDC " + ((LdcInsnNode) n).cst;
            } else if (n instanceof IntInsnNode) {
                s = "INT " + n.getOpcode() + " " + ((IntInsnNode) n).operand;
            } else if (n instanceof VarInsnNode) {
                s = "VAR " + n.getOpcode() + " " + ((VarInsnNode) n).var;
            } else if (n instanceof JumpInsnNode) {
                s = "J " + n.getOpcode() + " " + labels.get(((JumpInsnNode) n).label);
            } else if (n instanceof TypeInsnNode) {
                s = "T " + n.getOpcode() + " " + ((TypeInsnNode) n).desc;
            } else if (n instanceof IincInsnNode) {
                s = "IINC " + ((IincInsnNode) n).var + " " + ((IincInsnNode) n).incr;
            } else if (n instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode x = (TableSwitchInsnNode) n;
                StringBuilder sb = new StringBuilder("TS " + x.min + " " + x.max + " " + labels.get(x.dflt));
                for (LabelNode l : x.labels) sb.append(" ").append(labels.get(l));
                s = sb.toString();
            } else if (n instanceof LookupSwitchInsnNode) {
                LookupSwitchInsnNode x = (LookupSwitchInsnNode) n;
                StringBuilder sb = new StringBuilder("LS " + labels.get(x.dflt));
                for (int i = 0; i < x.keys.size(); i++) sb.append(" ").append(x.keys.get(i)).append(":").append(labels.get(x.labels.get(i)));
                s = sb.toString();
            } else if (n instanceof MultiANewArrayInsnNode) {
                s = "MA " + ((MultiANewArrayInsnNode) n).desc + " " + ((MultiANewArrayInsnNode) n).dims;
            } else {
                s = "OP " + n.getOpcode();
            }
            out.add(s);
        }
        for (TryCatchBlockNode t : m.tryCatchBlocks) {
            out.add("TRY " + labels.get(t.start) + " " + labels.get(t.end) + " " + labels.get(t.handler) + " " + t.type);
        }
        return out;
    }
}
