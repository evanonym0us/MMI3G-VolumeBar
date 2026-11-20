package de.audi.tghu.tone.app;

import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.model.AbstractModelBank;
import de.audi.atip.hmi.model.HMIModel;
import de.audi.atip.log.LogChannel;
import de.audi.atip.log.LogChannelFactory;
import de.audi.tghu.tone.app.conn.ConnectionStore;
import org.dsi.ifc.audiomanagement.DSIAudioManagementListener;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Worst case test: the two patched stock classes are present but the
 * VolumeBar classes are missing (as if they failed to load on the car).
 * The stock code must behave exactly as before. This file deliberately
 * never mentions VolumeBar.
 */
public class DegradedHarness {

    static int passed, failed;

    public static void main(String[] a) {
        AbstractModelBank.initLogging((LogChannelFactory) silent(LogChannelFactory.class));

        boolean missing;
        try {
            Class.forName("de.audi.tghu.tone.app.VolumeBar");
            missing = false;
        } catch (Throwable t) {
            missing = true;
        }
        check("VolumeBar classes really absent for this run", missing);

        ToneModelBank bank = null;
        Throwable err = null;
        try {
            bank = new ToneModelBank();
        } catch (Throwable t) {
            err = t;
        }
        check("ToneModelBank still constructs", bank != null && err == null);
        if (bank != null) {
            HMIModel m = bank.getModel(100027);
            check("fallback model fills slot 27", m != null && m.getID() == 100027);
            int[] ids = bank.getAllModelIds();
            boolean noZero = true;
            for (int i = 0; i < ids.length; i++) if (ids[i] == 0) noZero = false;
            check("getAllModelIds(): 27 ids, none empty", ids.length == 27 && noZero);
        }

        err = null;
        try {
            Tone t = new Tone();
            t.connStore.setActiveEntertainmentConnection(40);
            t.connStore.setActiveConnection(40);
            for (int v = 0; v < 40; v++) t.soundListener.updateVolume(40, (short) v, 1);
        } catch (Throwable t) {
            err = t;
        }
        check("SoundListener.updateVolume runs normally", err == null);
        if (err != null) err.printStackTrace();

        // Knob hooks: with VolumeBar missing, the stock code must be what runs.
        // Built without its constructor; the stock code then stops on the
        // missing sound system (NullPointerException), never on the hook.
        Throwable turn = null, press = null;
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) f.get(null);
            MenuVolumeHandler h = (MenuVolumeHandler) u.allocateInstance(MenuVolumeHandler.class);
            java.lang.reflect.Method m = MenuVolumeHandler.class.getDeclaredMethod("changeAudibleVolume", int.class);
            m.setAccessible(true);
            try {
                m.invoke(h, Integer.valueOf(1));
            } catch (java.lang.reflect.InvocationTargetException e) {
                turn = e.getCause();
            }
            try {
                h.keyTyped(100021);
            } catch (Throwable t) {
                press = t;
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        check("knob turn: hook skipped, stock code runs", turn instanceof NullPointerException);
        check("knob press: hook skipped, stock code runs", press instanceof NullPointerException);

        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static class Tone extends ToneAppBase {
        Tone() {
            super(new QuietLogger(), (HMIService) silent(HMIService.class), new ConnectionStore(),
                    new ToneModelBank(), null);
        }

        public DSIHandlerBase getDSIHandler() {
            return null;
        }

        short[] getSoundNotifications() {
            return new short[0];
        }

        DSIAudioManagementListener getDSIAudioManagementListener() {
            return null;
        }

        public int getId() {
            return 10;
        }
    }

    static class QuietLogger extends Logger {
        protected LogChannel getLogChannel(String name) {
            return (LogChannel) silent(LogChannel.class);
        }
    }

    static Object silent(final Class c) {
        return Proxy.newProxyInstance(DegradedHarness.class.getClassLoader(), new Class[] {c},
                new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] args) {
                        Class r = m.getReturnType();
                        if (r == LogChannel.class) return silent(LogChannel.class);
                        if (r == int.class) return Integer.valueOf(0);
                        if (r == boolean.class) return Boolean.FALSE;
                        if (r == long.class) return Long.valueOf(0);
                        return null;
                    }
                });
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (ok) passed++;
        else failed++;
    }
}
