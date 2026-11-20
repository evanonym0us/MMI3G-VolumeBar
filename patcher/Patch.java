import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Adds the volume bar hooks to stock classes from the converted K0133
 * lsd.jxe:
 *
 *   SoundListener.updateVolume            -> VolumeBar.onVolume
 *   MenuVolumeHandler.changeAudibleVolume -> VolumeBar.onKnob
 *   MenuVolumeHandler.keyTyped            -> VolumeBar.onKnobPress
 *   ToneModelBank()                       -> one extra model, VolumeBar.createModel
 *
 * Each hook is wrapped in try/catch(Throwable), so the stock code runs
 * even if the volume bar classes are missing. Each patch first checks
 * that the original code looks as expected and aborts otherwise, so a
 * different firmware version is never patched by accident.
 *
 * Usage: Patch <k0133.jar> <outDir>
 */
public class Patch implements Opcodes {

    static final String SL = "de/audi/tghu/tone/app/SoundListener";
    static final String TSF = "de/audi/tghu/tone/hmi/evolution/ToneScreenFactory";
    static final String TMB = "de/audi/tghu/tone/app/ToneModelBank";
    static final String VB = "de/audi/tghu/tone/app/VolumeBar";
    static final String MVH = "de/audi/tghu/tone/app/MenuVolumeHandler";

    public static void main(String[] a) throws Exception {
        ZipFile jar = new ZipFile(a[0]);
        File out = new File(a[1]);
        write(out, SL, patchSoundListener(read(jar, SL)));
        // ToneScreenFactory is not patched (the pop-up goes into its screen
        // cache at runtime), but its screen id must be free.
        checkScreenIdFree(read(jar, TSF));
        write(out, TMB, patchModelBank(read(jar, TMB)));
        write(out, MVH, patchMenuVolumeHandler(read(jar, MVH)));
        System.out.println("PATCH OK");
    }

    // ---- SoundListener.updateVolume(int conn, short vol, int valid) ----
    static byte[] patchSoundListener(ClassNode cn) {
        MethodNode m = method(cn, "updateVolume", "(ISI)V");
        FieldNode tone = field(cn, "toneBase", "Lde/audi/tghu/tone/app/ToneAppBase;");
        InsnList args = new InsnList();
        args.add(new VarInsnNode(ALOAD, 0));
        args.add(new FieldInsnNode(GETFIELD, cn.name, tone.name, tone.desc));
        args.add(new VarInsnNode(ILOAD, 1));
        args.add(new VarInsnNode(ILOAD, 2));
        args.add(new VarInsnNode(ILOAD, 3));
        insertHook(m, args, "onVolume", "(Lde/audi/tghu/tone/app/ToneAppBase;III)V");
        return toBytes(cn);
    }

    // ---- MenuVolumeHandler: knob turn and knob press ----
    // changeAudibleVolume(steps) turns a knob step into a volume change when
    // no volume menu is open. keyTyped(100021) is the knob press.
    static byte[] patchMenuVolumeHandler(ClassNode cn) {
        FieldNode tone = field(cn, "tone", "Lde/audi/tghu/tone/app/ToneAppFull;");
        if ((tone.access & ACC_STATIC) != 0) throw new IllegalStateException("tone is static");
        MethodNode turn = method(cn, "changeAudibleVolume", "(I)V");
        MethodNode press = method(cn, "keyTyped", "(I)V");
        requireCall(turn, "increaseVolume");
        requireCall(turn, "decreaseVolume");
        requireLdc(press, 100021);
        requireCall(press, "toggleOnOffButton");
        for (MethodNode m : new MethodNode[] {turn, press}) {
            InsnList args = new InsnList();
            args.add(new VarInsnNode(ALOAD, 0));
            args.add(new FieldInsnNode(GETFIELD, cn.name, tone.name, tone.desc));
            args.add(new VarInsnNode(ILOAD, 1));
            insertHook(m, args, m == turn ? "onKnob" : "onKnobPress",
                    "(Lde/audi/tghu/tone/app/ToneAppBase;I)V");
        }
        return toBytes(cn);
    }

    /** At the start of m: try { VolumeBar.name(args); } catch (Throwable t) { } */
    static void insertHook(MethodNode m, InsnList args, String name, String desc) {
        LabelNode s0 = new LabelNode(), s1 = new LabelNode(), hnd = new LabelNode(), after = new LabelNode();
        InsnList h = new InsnList();
        h.add(s0);
        h.add(args);
        h.add(new MethodInsnNode(INVOKESTATIC, VB, name, desc, false));
        h.add(s1);
        h.add(new JumpInsnNode(GOTO, after));
        h.add(hnd);
        h.add(new InsnNode(POP));
        h.add(after);
        m.tryCatchBlocks.add(0, new TryCatchBlockNode(s0, s1, hnd, "java/lang/Throwable"));
        m.instructions.insert(h);
    }

    static void requireCall(MethodNode m, String name) {
        for (AbstractInsnNode n : m.instructions) {
            if (n instanceof MethodInsnNode && ((MethodInsnNode) n).name.equals(name)) return;
        }
        throw new IllegalStateException(m.name + " does not call " + name);
    }

    static void requireLdc(MethodNode m, int v) {
        for (AbstractInsnNode n : m.instructions) {
            if (n instanceof LdcInsnNode && Integer.valueOf(v).equals(((LdcInsnNode) n).cst)) return;
        }
        throw new IllegalStateException(m.name + " has no constant " + v);
    }

    // ---- ToneScreenFactory.createScreen(int id): 100011 must be unused ----
    static void checkScreenIdFree(ClassNode cn) {
        MethodNode m = method(cn, "createScreen", "(I)Lde/audi/atip/hmi/view/Screen;");
        for (AbstractInsnNode n : m.instructions) {
            if (n instanceof TableSwitchInsnNode) {
                TableSwitchInsnNode t = (TableSwitchInsnNode) n;
                int idx = 100011 - t.min;
                if (idx >= 0 && idx < t.labels.size() && t.labels.get(idx) != t.dflt) {
                    throw new IllegalStateException("screen id 100011 already in use");
                }
            }
            if (n instanceof LookupSwitchInsnNode && ((LookupSwitchInsnNode) n).keys.contains(100011)) {
                throw new IllegalStateException("screen id 100011 already in use");
            }
        }
    }

    // ---- ToneModelBank(): count 26 -> 27, models[27] = VolumeBar.createModel() ----
    static byte[] patchModelBank(ClassNode cn) {
        MethodNode m = method(cn, "<init>", "()V");
        IntInsnNode count = null;
        int superCalls = 0;
        for (AbstractInsnNode n : m.instructions) {
            if (n instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) n;
                if (mi.getOpcode() == INVOKESPECIAL && mi.name.equals("<init>")
                        && mi.owner.equals("de/audi/atip/hmi/model/AbstractModelBank")
                        && mi.desc.equals("(III)V")) {
                    superCalls++;
                    AbstractInsnNode p = mi.getPrevious();
                    if (!(p instanceof IntInsnNode) || ((IntInsnNode) p).operand != 26) {
                        throw new IllegalStateException("unexpected model count before super()");
                    }
                    count = (IntInsnNode) p;
                    AbstractInsnNode size = p.getPrevious();
                    if (!(size instanceof IntInsnNode) || ((IntInsnNode) size).operand != 30) {
                        throw new IllegalStateException("unexpected model array size");
                    }
                }
            }
        }
        if (superCalls != 1 || count == null) {
            throw new IllegalStateException("super() call not found exactly once");
        }
        count.operand = 27;

        int returns = 0;
        for (AbstractInsnNode n : m.instructions) {
            if (n.getOpcode() == RETURN) returns++;
        }
        if (returns != 1) throw new IllegalStateException("expected one RETURN");
        for (AbstractInsnNode n : m.instructions.toArray()) {
            if (n.getOpcode() == RETURN) {
                // try { models[27] = VolumeBar.createModel(); }
                // catch (Throwable t) { models[27] = new RangeModel(100027); }
                // With the fallback the bank always holds 27 models.
                LabelNode s0 = new LabelNode(), s1 = new LabelNode(), hnd = new LabelNode(), after = new LabelNode();
                InsnList h = new InsnList();
                h.add(s0);
                h.add(new VarInsnNode(ALOAD, 0));
                h.add(new FieldInsnNode(GETFIELD, TMB, "models", "[Lde/audi/atip/hmi/model/HMIModel;"));
                h.add(new IntInsnNode(BIPUSH, 27));
                h.add(new MethodInsnNode(INVOKESTATIC, VB, "createModel",
                        "()Lde/audi/atip/hmi/model/HMIModel;", false));
                h.add(new InsnNode(AASTORE));
                h.add(s1);
                h.add(new JumpInsnNode(GOTO, after));
                h.add(hnd);
                h.add(new InsnNode(POP));
                h.add(new VarInsnNode(ALOAD, 0));
                h.add(new FieldInsnNode(GETFIELD, TMB, "models", "[Lde/audi/atip/hmi/model/HMIModel;"));
                h.add(new IntInsnNode(BIPUSH, 27));
                h.add(new TypeInsnNode(NEW, "de/audi/atip/hmi/model/RangeModel"));
                h.add(new InsnNode(DUP));
                h.add(new LdcInsnNode(Integer.valueOf(100027)));
                h.add(new MethodInsnNode(INVOKESPECIAL, "de/audi/atip/hmi/model/RangeModel", "<init>", "(I)V", false));
                h.add(new InsnNode(AASTORE));
                h.add(after);
                m.instructions.insertBefore(n, h);
                m.tryCatchBlocks.add(new TryCatchBlockNode(s0, s1, hnd, "java/lang/Throwable"));
            }
        }
        return toBytes(cn);
    }

    // ---- helpers ----
    static ClassNode read(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name + ".class");
        if (e == null) throw new FileNotFoundException(name);
        ClassNode cn = new ClassNode();
        new ClassReader(z.getInputStream(e)).accept(cn, 0);
        if (cn.version != 46) throw new IllegalStateException(name + " has class version " + cn.version);
        return cn;
    }

    static MethodNode method(ClassNode cn, String name, String desc) {
        MethodNode found = null;
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                if (found != null) throw new IllegalStateException("duplicate " + name);
                found = m;
            }
        }
        if (found == null) throw new IllegalStateException(cn.name + "." + name + desc + " not found");
        return found;
    }

    static FieldNode field(ClassNode cn, String name, String desc) {
        for (FieldNode f : cn.fields) {
            if (f.name.equals(name) && f.desc.equals(desc)) return f;
        }
        throw new IllegalStateException(cn.name + "." + name + " not found");
    }

    static byte[] toBytes(ClassNode cn) {
        // Version 46: no stack map frames. Only recompute max stack/locals.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static void write(File dir, String name, byte[] b) throws IOException {
        File f = new File(dir, name + ".class");
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), b);
    }
}
