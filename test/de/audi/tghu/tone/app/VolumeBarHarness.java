package de.audi.tghu.tone.app;

import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.HMIServicePopin;
import de.audi.atip.hmi.model.HMIModel;
import de.audi.atip.hmi.model.RangeModel;
import de.audi.atip.log.LogChannel;
import de.audi.tghu.tone.app.conn.ConnectionStore;
import de.audi.tghu.tone.hmi.evolution.VolumeBarShapes;
import org.dsi.ifc.audiomanagement.DSIAudioManagementListener;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Desktop test for the volume bar. Runs the real (patched) SoundListener,
 * the real (patched) ToneModelBank, the real ConnectionStore and RangeModel
 * from K0133, with a fake HMI service that records popin calls.
 *
 * Volume events go through SoundListener.updateVolume exactly as the DSI
 * layer delivers them on the car, so the hook itself is exercised.
 */
public class VolumeBarHarness {

    static int passed, failed;
    static final List<String> calls = new ArrayList<String>();
    static boolean popinThrows;

    static long fakeNow = 1000000L;

    public static void main(String[] a) throws Exception {
        // On the car the framework sets up model logging before any model
        // exists. Do the same here with a silent log channel.
        de.audi.atip.hmi.model.AbstractModelBank.initLogging(
                (de.audi.atip.log.LogChannelFactory) Proxy.newProxyInstance(
                        VolumeBarHarness.class.getClassLoader(),
                        new Class[] {de.audi.atip.log.LogChannelFactory.class},
                        new InvocationHandler() {
                            public Object invoke(Object p, Method m, Object[] args) {
                                if (m.getReturnType() == LogChannel.class) return silentChannel();
                                return null;
                            }
                        }));
        VolumeBar.logPath = new File(a.length > 0 ? a[0] : "volumebar-test.log").getAbsolutePath();
        new File(VolumeBar.logPath).delete();

        VolumeBar.clock = new VolumeBar.Clock() {
            long now() {
                return fakeNow;
            }
        };
        VolumeBar.ui = new FakeUi();

        modelBankTests();
        shapeTests();
        widgetTests();
        screenSlotTests();
        flowTests();
        hnPlusTests();
        knobHookTests();
        fadeTests();
        layoutTests();
        failureTests();

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------

    static void modelBankTests() {
        section("Model bank (patched ToneModelBank)");
        VolumeBar.resetForTest();
        ToneModelBank bank = new ToneModelBank();
        HMIModel m = bank.getModel(VolumeBar.MODEL_ID);
        check("model 100027 is registered", m != null && m.getID() == VolumeBar.MODEL_ID);
        check("model is a RangeModel", m instanceof RangeModel);
        int[] ids = null;
        Throwable err = null;
        try {
            ids = bank.getAllModelIds();
        } catch (Throwable t) {
            err = t;
        }
        check("getAllModelIds() still works (no overflow)", err == null);
        check("getAllModelIds() returns 27 ids", ids != null && ids.length == 27);
        check("getAllModelIds() includes 100027", contains(ids, VolumeBar.MODEL_ID));
        check("stock models untouched (100025 traffic volume)", bank.getModel(100025) != null
                && bank.getModel(100025).getID() == 100025);
        check("unused slot 28 still empty", bank.getModel(100028) == null);
    }

    static void shapeTests() {
        section("Drawing geometry (matches the stock tool-tip shape)");
        float w = 300, h = 80;
        // ToolTipWidget.calculateRoundRectPoly(), radius 5, copied from the firmware
        float[] stock = {5, 0, w - 5, 0, 5, 5, 0, w, 5, w, h - 5, 5, 5, 0, w - 5, h,
                5, h, 5, 5, 0, 0, h - 5, 0, 5, 5, 5, 0, 5, 0};
        float[] ours = de.audi.tghu.tone.hmi.evolution.VolumeBarShapes.roundRect(0, 0, w, h, 5);
        boolean same = stock.length == ours.length;
        for (int i = 0; same && i < stock.length; i++) same = stock[i] == ours[i];
        check("rounded rectangle identical to the firmware's own (radius 5)", same);
        check("command bytes identical to the firmware's own",
                java.util.Arrays.equals(de.audi.tghu.tone.hmi.evolution.VolumeBarShapes.ROUND_RECT_CMDS,
                        new byte[] {2, 4, 20, 4, 20, 4, 20, 4, 20, 0}));
        float[] pill = de.audi.tghu.tone.hmi.evolution.VolumeBarShapes.roundRect(10, 10, 100, 8, 50);
        check("radius clamped to half height (pill shape)", pill[4] == 4 && pill[5] == 4);
        check("knob at 0 sits at track start",
                de.audi.tghu.tone.hmi.evolution.VolumeBarShapes.knobX(0, 30, 10, 350) == 10);
        check("knob at max sits at track end",
                de.audi.tghu.tone.hmi.evolution.VolumeBarShapes.knobX(30, 30, 10, 350) == 350);
        check("knob at 15/30 is centred",
                de.audi.tghu.tone.hmi.evolution.VolumeBarShapes.knobX(15, 30, 10, 350) == 180);
        check("knob clamps out-of-range levels",
                de.audi.tghu.tone.hmi.evolution.VolumeBarShapes.knobX(45, 30, 10, 350) == 350
                && de.audi.tghu.tone.hmi.evolution.VolumeBarShapes.knobX(-3, 30, 10, 350) == 10);
        check("ramp stays inside its 36 x 17 box", inside(VolumeBarShapes.ramp(0, 0), 0, 0, 36, 17));
        check("ramp empty at 0", VolumeBarShapes.rampFill(0, 0, 0, 34) == null);
        float[] half = VolumeBarShapes.rampFill(0, 0, 17, 34);
        float[] full = VolumeBarShapes.rampFill(0, 0, 34, 34);
        float[] ramp = VolumeBarShapes.ramp(0, 0);
        check("ramp half full at 17: fill edge in the middle, on the slope",
                Math.abs(half[2] - 18) < 0.01f && Math.abs(half[3] - 8.5f) < 0.01f && half[4] == half[2]);
        check("ramp completely full at 34 (same corners as the outline)",
                full[2] == ramp[2] && full[3] == ramp[3] && full[0] == ramp[0] && full[1] == ramp[1]);
        float[] over = VolumeBarShapes.rampFill(0, 0, 40, 34);
        check("ramp fill never overflows", over[2] == ramp[2] && inside(over, 0, 0, 36, 17));
        boolean grows = true;
        float lastX = 0;
        for (int v = 1; v <= 34; v++) {
            float x = VolumeBarShapes.rampFill(0, 0, v, 34)[2];
            if (x <= lastX) grows = false;
            lastX = x;
        }
        check("ramp fill grows with every step", grows);
        check("\"Max\" at the top, number below it, nothing when the mute symbol shows",
                "Max".equals(de.audi.tghu.tone.hmi.evolution.VolumeNumberLabel.textFor(34, 34, false))
                && "33".equals(de.audi.tghu.tone.hmi.evolution.VolumeNumberLabel.textFor(33, 34, false))
                && de.audi.tghu.tone.hmi.evolution.VolumeNumberLabel.textFor(12, 12, false).equals("Max")
                && "".equals(de.audi.tghu.tone.hmi.evolution.VolumeNumberLabel.textFor(0, 34, true)));
        check("mute speaker and slash stay inside the 38 x 27 slot",
                inside(VolumeBarShapes.muteSpeaker(0, 0), 0, 0, 38, 27) && inside(VolumeBarShapes.muteSlash(0, 0), 0, 0, 38, 27));
        check("path command/coordinate counts match",
                VolumeBarShapes.ramp(0, 0).length == 6 && VolumeBarShapes.RAMP_CMDS.length == 4
                && VolumeBarShapes.rampFill(0, 0, 5, 34).length == 6 && VolumeBarShapes.RAMP_FILL_CMDS.length == 4
                && VolumeBarShapes.muteSlash(0, 0).length == 4 && VolumeBarShapes.SLASH_CMDS.length == 2
                && VolumeBarShapes.speaker(0, 0).length == 12);
    }

    static boolean inside(float[] p, float x0, float y0, float x1, float y1) {
        for (int i = 0; i + 1 < p.length; i += 2) {
            if (p[i] < x0 || p[i] > x1 || p[i + 1] < y0 || p[i + 1] > y1) return false;
        }
        return true;
    }

    static void widgetTests() throws Exception {
        section("Level bar widget repaint rule (managePaint renders only invalid widgets)");
        de.audi.tghu.tone.hmi.evolution.VolumeLevelWidget w = null;
        Throwable err = null;
        try {
            w = new de.audi.tghu.tone.hmi.evolution.VolumeLevelWidget(0, 0, 290, 24);
        } catch (Throwable t) {
            err = t;
        }
        check("widget constructs off-device", w != null);
        if (w == null) {
            System.out.println("        " + err);
            return;
        }
        w.setCompositesDirty(false);
        check("starts clean", !w.isInvalid());
        w.setCompositesDirty(true);
        check("refresh marks it invalid, so the bar gets redrawn", w.isInvalid());

        de.audi.tghu.tone.hmi.evolution.VolumeTitleLabel tl = new de.audi.tghu.tone.hmi.evolution.VolumeTitleLabel(0, 0, 100, 30);
        de.audi.tghu.tone.hmi.evolution.VolumeNumberLabel nl = new de.audi.tghu.tone.hmi.evolution.VolumeNumberLabel(0, 0, 70, 40);
        tl.setOpacity(0f);   // what the MMI's screen-change animation does to every label
        nl.setOpacity(0f);
        check("screen-change fades can't make the title or number invisible",
                opacityOf(tl) == 1f && opacityOf(nl) == 1f);
        de.audi.tghu.hmi.view.LabelWidget l = new de.audi.tghu.hmi.view.LabelWidget(0, 0, 50, 40);
        check("stock label is the reference: also invalid after setCompositesDirty",
                invalidAfterDirty(l));
    }

    static boolean invalidAfterDirty(de.audi.tghu.hmi.view.AbstractWidget w) {
        w.setCompositesDirty(false);
        w.setCompositesDirty(true);
        return w.isInvalid();
    }

    static void screenSlotTests() {
        section("Screen slot (stock ToneScreenFactory, unmodified)");
        Throwable err = null;
        de.audi.tghu.tone.hmi.evolution.ToneScreenFactory f = null;
        try {
            f = de.audi.tghu.tone.hmi.evolution.ToneScreenFactory.getInstance();
        } catch (Throwable t) {
            err = t;
        }
        check("ToneScreenFactory available", f != null);
        if (f == null) {
            System.out.println("        " + err);
            return;
        }
        de.audi.atip.hmi.view.Screen dummy = (de.audi.atip.hmi.view.Screen) Proxy.newProxyInstance(
                VolumeBarHarness.class.getClassLoader(),
                new Class[] {de.audi.atip.hmi.view.Screen.class}, new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] args) {
                        if (m.getName().equals("getID")) return Integer.valueOf(VolumeBar.SCREEN_ID);
                        if (m.getReturnType() == int.class) return Integer.valueOf(0);
                        if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                        return null;
                    }
                });
        check("slot 100011 empty in stock firmware",
                de.audi.atip.hmi.view.VolumeBarSlot.get(f, VolumeBar.SCREEN_ID) == null);
        check("install into slot", de.audi.atip.hmi.view.VolumeBarSlot.install(f, VolumeBar.SCREEN_ID, dummy));
        check("factory.getScreen(100011) returns our screen (createScreen not involved)",
                f.getScreen(VolumeBar.SCREEN_ID) == dummy);
        de.audi.atip.hmi.view.Screen other = (de.audi.atip.hmi.view.Screen) Proxy.newProxyInstance(
                VolumeBarHarness.class.getClassLoader(),
                new Class[] {de.audi.atip.hmi.view.Screen.class}, new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] args) {
                        return null;
                    }
                });
        check("never overwrites an occupied slot",
                !de.audi.atip.hmi.view.VolumeBarSlot.install(f, VolumeBar.SCREEN_ID, other)
                        && f.getScreen(VolumeBar.SCREEN_ID) == dummy);
        check("refuses ids from another module",
                !de.audi.atip.hmi.view.VolumeBarSlot.install(f, 60011, dummy));
        check("refuses ids past the end of the cache",
                !de.audi.atip.hmi.view.VolumeBarSlot.install(f, 100026, dummy));
    }

    static void flowTests() throws Exception {
        section("Volume events through SoundListener.updateVolume");
        VolumeBar.resetForTest();
        VolumeBar.ui = new FakeUi();
        calls.clear();
        Tone tone = newTone();
        ConnectionStore cs = tone.connStore;
        SoundListener sl = tone.soundListener;
        RangeModel model = (RangeModel) tone.modelBank.getModel(VolumeBar.MODEL_ID);

        int MEDIA = 40, NAV = 83;
        cs.setActiveEntertainmentConnection(MEDIA);
        cs.setActiveConnection(MEDIA);

        // Boot: the system reports the starting volume (and may correct it).
        sl.updateVolume(MEDIA, (short) 12, 1);
        sl.updateVolume(MEDIA, (short) 14, 1);
        check("no popin during startup quiet period", countShow() == 0);
        check("model follows volume during startup", model.getValue() == 14);

        fakeNow += 25000; // past the quiet period

        sl.updateVolume(MEDIA, (short) 15, 1);
        check("knob turn after startup shows popin", countShow() == 1);
        check("popin screen installed before show", calls.indexOf("ensureInstalled") < calls.indexOf("show"));
        check("model value = 15", model.getValue() == 15);
        check("model limits 0..34 (car maximum)", model.getMinimum() == 0 && model.getMaximum() == 34);

        sl.updateVolume(MEDIA, (short) 16, 1);
        sl.updateVolume(MEDIA, (short) 17, 1);
        check("further turns don't re-show (already visible)", countShow() == 1);
        check("model value = 17", model.getValue() == 17);

        sl.updateVolume(MEDIA, (short) 17, 1);
        check("same level repeated: no new show", countShow() == 1);

        sl.updateVolume(NAV, (short) 20, 1);
        check("nav volume while music has focus: ignored", model.getValue() == 17);

        sl.updateVolume(MEDIA, (short) 18, 2);
        check("invalid DSI report (flag 2) ignored", model.getValue() == 17);

        Thread.sleep(3600);
        check("popin hidden ~3 s after last change", countRemove() == 1 && !VolumeBar.isShownForTest());

        sl.updateVolume(MEDIA, (short) 19, 1);
        check("next turn shows again", countShow() == 2);
        Thread.sleep(1500);
        sl.updateVolume(MEDIA, (short) 20, 1);
        Thread.sleep(2000);
        check("turning keeps it up (timer restarts)", countRemove() == 1 && VolumeBar.isShownForTest());
        Thread.sleep(1500);
        check("then hides", countRemove() == 2);
        check("screen built/connected only once (later shows are direct)", countConnectShow() == 1);
        check("framework removePopin never used (stays connected)", count("remove") == 0);
        check("bar widget sees the level", VolumeBar.level() == 20 && VolumeBar.levelMax() == 34);

        section("Source changes and focus");
        int RADIO = 26;   // DAB tuner
        cs.setActiveEntertainmentConnection(RADIO);
        cs.setActiveConnection(RADIO);
        sl.updateVolume(RADIO, (short) 10, 1);
        check("first report after source switch: no popin", countShow() == 2);
        check("model shows new source level", model.getValue() == 10);
        sl.updateVolume(RADIO, (short) 11, 1);
        check("turn on new source shows popin", countShow() == 3);
        Thread.sleep(3600);

        cs.setActiveConnection(NAV); // nav prompt is speaking, knob controls nav
        sl.updateVolume(NAV, (short) 20, 1);
        sl.updateVolume(NAV, (short) 22, 1);
        check("nav volume while nav has focus: shown", countShow() == 4 && model.getValue() == 22);
        Thread.sleep(3600);
        cs.setActiveConnection(RADIO);

        section("Work per knob step (lag)");
        CountingStats stats = new CountingStats();
        de.audi.atip.hmi.model.AbstractModel.statistics = stats;
        sl.updateVolume(RADIO, (short) 12, 1);
        VolumeBar.onKnob(tone, 1);
        check("pop-up is up for this section", VolumeBar.isShownForTest());
        int after = stats.values;
        int applies = count("apply");
        sl.updateVolume(RADIO, (short) 12, 1);
        sl.updateVolume(RADIO, (short) 12, 1);
        check("repeated identical reports cause no repaint", stats.values == after && count("apply") == applies);
        VolumeBar.onKnob(tone, 1);
        VolumeBar.onKnob(tone, 1);
        check("knob turns don't repaint by themselves (the new level does)",
                stats.values == after && count("apply") == applies);
        sl.updateVolume(RADIO, (short) 13, 1);
        check("one level change while shown: one model update, one direct repaint",
                stats.values == after + 1 && count("apply") == applies + 1);
        de.audi.atip.hmi.model.AbstractModel.statistics = null;
        Thread.sleep(3600);

        section("Hiding on a screen change");
        FakeUi ui = fakeUi();
        ui.screen = 10;
        sl.updateVolume(RADIO, (short) 14, 1);
        check("shown on screen 10", VolumeBar.isShownForTest());
        Thread.sleep(500);
        check("stays up while the screen stays the same", VolumeBar.isShownForTest());
        int fades = count("hideFade");
        ui.screen = 20;
        Thread.sleep(500);
        check("hidden within half a second of the screen changing", !VolumeBar.isShownForTest());
        check("screen change: gone at once, no fade on top of the screen transition",
                count("hideInstant") == 1 && count("hideFade") == fades);
        sl.updateVolume(RADIO, (short) 15, 1);
        check("next change shows it again on the new screen", VolumeBar.isShownForTest());
        Thread.sleep(500);
        check("and it stays up there", VolumeBar.isShownForTest());
        Thread.sleep(3300);
        check("still hides 3 s after the last change", !VolumeBar.isShownForTest());
        check("the timeout fades out", count("hideFade") == fades + 1);
        sl.updateVolume(RADIO, (short) 30, 1);
        check("up again on screen 20", VolumeBar.isShownForTest());
        int blinks = count("hideInstant");
        ui.screen = 30;
        sl.updateVolume(RADIO, (short) 31, 1);   // still turning as the screen changes
        Thread.sleep(500);
        check("still turning through a screen change: stays up, no blink",
                VolumeBar.isShownForTest() && count("hideInstant") == blinks);
        ui.screen = 40;
        Thread.sleep(500);
        check("a later screen change without turning still hides it", !VolumeBar.isShownForTest());
        Thread.sleep(300);

        ui.screen = -1;
        sl.updateVolume(RADIO, (short) 16, 1);
        Thread.sleep(500);
        check("screen id unreadable: only the 3 s timeout applies", VolumeBar.isShownForTest());
        Thread.sleep(3300);

        section("Rear view camera");
        ui.camera = true;
        sl.updateVolume(RADIO, (short) 17, 1);
        VolumeBar.onKnob(tone, 1);
        check("never shows over the camera", !VolumeBar.isShownForTest());
        check("level still followed while the camera is on", VolumeBar.level() == 17);
        ui.camera = false;
        VolumeBar.onKnob(tone, 1);
        check("shows again once the camera is off", VolumeBar.isShownForTest());
        int instants = count("hideInstant");
        ui.camera = true;
        Thread.sleep(500);
        check("hides within half a second of the camera coming on", !VolumeBar.isShownForTest());
        check("camera: gone at once, no fade over the camera view", count("hideInstant") == instants + 1);
        ui.camera = false;

        section("Scale");
        sl.updateVolume(RADIO, (short) 11, 1);
        sl.updateVolume(RADIO, (short) 34, 1);
        check("34 is the top of the scale (no stretch)", VolumeBar.maxForTest() == 34 && model.getMaximum() == 34);
        sl.updateVolume(RADIO, (short) 40, 1);
        check("a level above 34 would still stretch the scale", VolumeBar.maxForTest() == 40 && model.getMaximum() == 40);
        Thread.sleep(3600);
    }


    static void hnPlusTests() throws Exception {
        section("3G+ behaviour: titles, knob, mute symbol, lock");
        VolumeBar.resetForTest();
        VolumeBar.ui = new FakeUi();
        calls.clear();
        Tone tone = newTone();
        ConnectionStore cs = tone.connStore;
        SoundListener sl = tone.soundListener;
        RangeModel model = (RangeModel) tone.modelBank.getModel(VolumeBar.MODEL_ID);
        int MEDIA = 40;
        cs.setActiveEntertainmentConnection(MEDIA);
        cs.setActiveConnection(MEDIA);
        cs.startConnection(MEDIA);
        sl.updateVolume(MEDIA, (short) 34, 1);
        fakeNow += 25000;

        check("connection groups match the 3G High volume menu",
                VolumeBar.titleOf(116) == VolumeBar.T_NAV && VolumeBar.titleOf(83) == VolumeBar.T_NAV
                && VolumeBar.titleOf(112) == VolumeBar.T_SDS && VolumeBar.titleOf(98) == VolumeBar.T_RING
                && VolumeBar.titleOf(104) == VolumeBar.T_CALL && VolumeBar.titleOf(90) == VolumeBar.T_CALL
                && VolumeBar.titleOf(31) == VolumeBar.T_TA && VolumeBar.titleOf(114) == VolumeBar.T_TMC
                && VolumeBar.titleOf(MEDIA) == VolumeBar.T_ENT && VolumeBar.titleOf(22) == VolumeBar.T_ENT);

        int appliesBefore = count("apply");
        VolumeBar.onKnob(tone, 1);
        check("knob turn at the top of the scale still shows", countShow() == 1);
        check("showing queues no separate repaint (the pop-up refreshes as it appears)",
                count("apply") == appliesBefore);
        check("title: entertainment", VolumeBar.title() == VolumeBar.T_ENT);
        check("shows the cached level 34", model.getValue() == 34 && VolumeBar.level() == 34);
        check("no mute symbol above 0", !VolumeBar.showMute());

        VolumeBar.onKnobPress(tone, 100021);
        check("knob press hides at once, with the fade", countRemove() == 1 && !VolumeBar.isShownForTest()
                && count("hideFade") >= 1);
        VolumeBar.onKnobPress(tone, 100005);
        check("other buttons don't hide anything", countRemove() == 1);

        // Down to 0 and back up. On the car the MMI starts its mute
        // connection (8) at 0 and may not stop it again; the level must
        // still follow.
        sl.updateVolume(MEDIA, (short) 0, 1);
        check("entertainment at 0: mute symbol, no number", VolumeBar.showMute() && model.getValue() == 0);
        cs.startConnection(8);
        sl.updateVolume(MEDIA, (short) 1, 1);
        VolumeBar.onKnob(tone, 1);
        check("0 -> 1 with the mute connection still started: shows 1", !VolumeBar.showMute()
                && model.getValue() == 1 && VolumeBar.level() == 1);
        sl.updateVolume(MEDIA, (short) 5, 1);
        check("keeps following after that", VolumeBar.level() == 5 && model.getValue() == 5);
        cs.stopConnection(8);
        sl.updateVolume(MEDIA, (short) 1, 1);

        sl.updateVolume(16, (short) 30, 1);   // AUX in the background, not listened to
        sl.updateVolume(85, (short) 9, 1);    // a connection we don't know
        check("background and unknown connections don't change the level",
                VolumeBar.level() == 1 && model.getValue() == 1 && VolumeBar.maxForTest() == 34);

        // navigation prompt speaking: knob changes nav volume
        sl.updateVolume(117, (short) 12, 1);   // not focused: just remembered
        check("other source's level remembered but not shown", model.getValue() == 1);
        cs.setActiveConnection(117);   // nav prompt is what you hear
        VolumeBar.onKnob(tone, 1);
        check("nav prompt active: title navigation, its own level",
                VolumeBar.title() == VolumeBar.T_NAV && model.getValue() == 12);
        sl.updateVolume(117, (short) 0, 1);
        check("non-entertainment at 0 keeps the number", !VolumeBar.showMute() && model.getValue() == 0);
        cs.setActiveConnection(MEDIA);

        // phone call (Bluetooth downlink, as seen on the car)
        cs.startConnection(104);   // phone connected, music still playing
        VolumeBar.onKnob(tone, 1);
        check("phone connected but music audible: entertainment", VolumeBar.title() == VolumeBar.T_ENT);
        cs.setActiveConnection(104);
        int applies = count("apply");
        VolumeBar.onKnob(tone, 1);
        check("in a call: title call volume", VolumeBar.title() == VolumeBar.T_CALL);
        check("title refreshed while already showing", count("apply") > applies);
        cs.setActiveConnection(MEDIA);
        cs.stopConnection(104);

        // volume locked
        cs.setActiveConnection(5);
        VolumeBar.onKnob(tone, 1);
        check("volume lock: unavailable, row dimmed", VolumeBar.locked() && VolumeBar.title() == VolumeBar.T_ENT);
        check("volume lock title text", "Currently unavailable".equals(
                de.audi.tghu.tone.hmi.evolution.VolumeBarScreen.titleText()));
        cs.setActiveConnection(MEDIA);
        VolumeBar.onKnob(tone, 1);
        check("lock released: normal again", !VolumeBar.locked());

        check("titles: 3G+ wording", "Entertainment volume".equals(tt(VolumeBar.T_ENT, false))
                && "Navigation prompts volume".equals(tt(VolumeBar.T_NAV, false))
                && "Call volume".equals(tt(VolumeBar.T_CALL, false))
                && "Ring tone volume".equals(tt(VolumeBar.T_RING, false))
                && "Voice recognition volume".equals(tt(VolumeBar.T_SDS, false))
                && "Traffic reports volume".equals(tt(VolumeBar.T_TA, false))
                && "Currently unavailable".equals(tt(VolumeBar.T_NAV, true)));
        Thread.sleep(3600);
    }

    static String tt(int title, boolean locked) {
        return de.audi.tghu.tone.hmi.evolution.VolumeBarScreen.titleFor(title, locked);
    }

    /**
     * The real patched MenuVolumeHandler from K0133. It is built without
     * running its constructor (that needs the whole sound system); only
     * the fields the hook and the stock code read are filled in.
     */
    static void knobHookTests() throws Exception {
        section("Knob hooks in the real (patched) MenuVolumeHandler");
        VolumeBar.resetForTest();
        VolumeBar.ui = new FakeUi();
        calls.clear();
        Tone base = newTone();
        base.connStore.setActiveEntertainmentConnection(40);
        base.connStore.setActiveConnection(40);
        base.soundListener.updateVolume(40, (short) 20, 1);
        fakeNow += 25000;

        sun.misc.Unsafe u = unsafe();
        ToneAppFull full = (ToneAppFull) u.allocateInstance(ToneAppFull.class);
        setField(ToneAppBase.class, full, "connStore", base.connStore);
        setField(ToneAppBase.class, full, "modelBank", base.modelBank);
        setField(ToneAppBase.class, full, "hmiService", base.hmiService);
        MenuVolumeHandler h = (MenuVolumeHandler) u.allocateInstance(MenuVolumeHandler.class);
        setField(MenuVolumeHandler.class, h, "tone", full);

        Method turn = MenuVolumeHandler.class.getDeclaredMethod("changeAudibleVolume", int.class);
        turn.setAccessible(true);
        Throwable stock = null;
        try {
            turn.invoke(h, Integer.valueOf(1));
        } catch (java.lang.reflect.InvocationTargetException e) {
            stock = e.getCause();   // the stock code after the hook has no DSI here
        }
        check("knob turn reaches the pop-up through the hook", countShow() == 1);
        check("stock code still runs after the hook", stock != null);
        stock = null;
        try {
            h.keyTyped(100021);
        } catch (Throwable t) {
            stock = t;
        }
        check("knob press reaches the pop-up through the hook", countRemove() == 1);
        check("stock knob-press code still runs after the hook", stock != null);
        Thread.sleep(100);
    }

    static sun.misc.Unsafe unsafe() throws Exception {
        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    static void setField(Class c, Object o, String name, Object v) throws Exception {
        java.lang.reflect.Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        java.lang.reflect.Field mods = java.lang.reflect.Field.class.getDeclaredField("modifiers");
        mods.setAccessible(true);
        mods.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
        f.set(o, v);
    }

    static void fadeTests() {
        section("Fade");
        de.audi.tghu.tone.hmi.evolution.VolumeBarFade f = new de.audi.tghu.tone.hmi.evolution.VolumeBarFade(0f);
        long t0 = 5000;
        f.start(1f, t0);
        float first = f.step(t0);
        check("the very first frame is already mostly visible: " + fmt(first), first > 0.55f && f.isRunning());
        check("fully visible within 150 ms, then stops", f.step(t0 + 150) == 1f && !f.isRunning());
        check("fade in takes at most 4 frames", frames(0f, 1f) <= 4);
        f.start(0f, t0 + 1000);
        float out0 = f.step(t0 + 1000);
        check("fade out's first frame already moves: " + fmt(out0), out0 < 1f && out0 > 0.8f);
        float mid = f.step(t0 + 1070);
        check("fade out is smooth (about half way at half time): " + fmt(mid), mid > 0.4f && mid < 0.6f);
        check("gone within 220 ms", f.step(t0 + 1220) == 0f && !f.isRunning());
        check("fade out takes at most 6 frames", frames(1f, 0f) <= 6);
        // turn the knob again while it is fading out
        f.set(1f);
        f.start(0f, 0);
        float at = f.step(80);
        f.start(1f, 80);
        float back = f.step(80);
        check("reverses from where it is: first frame moves towards visible, never back: "
                + fmt(at) + " -> " + fmt(back), back > at && back < 1f);
        check("and is back to full quickly", f.step(80 + 150) == 1f);
        boolean mono = true;
        f.set(0f);
        f.start(1f, 0);
        float last = 0f;
        for (int t = 0; t <= 160; t += 5) {
            float v = f.step(t);
            if (v < last - 0.0001f || v < 0f || v > 1f) mono = false;
            last = v;
        }
        check("fade in never steps backwards or overshoots", mono);
        f.start(1f, 200);
        check("show while already shown: nothing to animate", !f.isRunning() && f.value() == 1f);
    }

    static int frames(float from, float to) {
        de.audi.tghu.tone.hmi.evolution.VolumeBarFade f = new de.audi.tghu.tone.hmi.evolution.VolumeBarFade(from);
        f.start(to, 0);
        int n = 0;
        long t = 0;
        f.step(t);   // first frame is applied immediately
        while (f.isRunning() && n < 100) {
            t += de.audi.tghu.tone.hmi.evolution.VolumeBarFade.FRAME_MS;
            f.step(t);
            n++;
        }
        return n;
    }

    static class CountingStats extends de.audi.atip.util.ModelStatistics {
        int values;

        public synchronized void countValueChanged(int id) {
            if (id == VolumeBar.MODEL_ID) values++;
        }
    }

    static String fmt(float v) {
        return String.valueOf(Math.round(v * 100) / 100.0);
    }

    static void layoutTests() throws Exception {
        section("Layout");
        int x = getInt("X"), y = getInt("Y"), w = getInt("W"), h = getInt("H");
        check("panel inside the screen and centred", x >= 0 && x + w <= 800 && Math.abs((800 - w) / 2 - x) <= 1);
        check("panel clear of the clock/status line (y 422)", y + h < 422 - 10);
        check("panel below the media content box (y 288)", y > 288);
        check("number slot ends where the level row ends",
                getInt("NUMBER_X") + getInt("NUMBER_W") == getInt("PAD") + de.audi.tghu.tone.hmi.evolution.VolumeLevelWidget.WIDTH);
        check("level row: 36 ramp + 15 + 245 bar + 15 + 66 slot",
                de.audi.tghu.tone.hmi.evolution.VolumeLevelWidget.WIDTH == 36 + 15 + 245 + 15 + 66);
        check("number label wide enough for \"Max\" in the 27 px font (about 55 px on the car)",
                getInt("NUMBER_W") >= 70);
        check("rows don't overlap and fit the panel",
                getInt("TITLE_Y") + getInt("TITLE_H") <= getInt("ROW_Y") && getInt("ROW_Y") + getInt("ROW_H") <= h);
    }

    static float opacityOf(de.audi.tghu.hmi.view.AbstractWidget w) throws Exception {
        java.lang.reflect.Field f = de.audi.tghu.hmi.view.AbstractWidget.class.getDeclaredField("opacity");
        f.setAccessible(true);
        return f.getFloat(w);
    }

    static int getInt(String name) throws Exception {
        java.lang.reflect.Field f = de.audi.tghu.tone.hmi.evolution.VolumeBarScreen.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    static void failureTests() throws Exception {
        section("Failure handling");
        VolumeBar.resetForTest();
        VolumeBar.ui = new FakeUi();
        calls.clear();
        Tone tone = newTone();
        ConnectionStore cs = tone.connStore;
        SoundListener sl = tone.soundListener;
        cs.setActiveEntertainmentConnection(40);
        cs.setActiveConnection(40);
        sl.updateVolume(40, (short) 10, 1);
        fakeNow += 25000;

        popinThrows = true;
        Throwable leaked = null;
        try {
            for (int v = 11; v < 20; v++) {
                sl.updateVolume(40, (short) v, 1);
            }
        } catch (Throwable t) {
            leaked = t;
        }
        popinThrows = false;
        check("errors never reach the stock volume code", leaked == null);
        check("disables itself after 5 errors", VolumeBar.isDisabled());
        int before = countShow();
        sl.updateVolume(40, (short) 25, 1);
        check("once disabled, does nothing", countShow() == before);

        VolumeBar.resetForTest();
        calls.clear();
        VolumeBar.Ui saved = VolumeBar.ui;
        VolumeBar.ui = new FakeUi() {
            boolean ensureInstalled() {
                return false;
            }
        };
        tone = newTone();
        tone.connStore.setActiveEntertainmentConnection(40);
        tone.connStore.setActiveConnection(40);
        tone.soundListener.updateVolume(40, (short) 10, 1);
        fakeNow += 25000;
        tone.soundListener.updateVolume(40, (short) 11, 1);
        check("no popin screen: never calls showPopin", countShow() == 0);
        check("no popin screen: turns itself off", VolumeBar.isDisabled());
        VolumeBar.ui = saved;

        VolumeBar.resetForTest();
        calls.clear();
        Tone noPopin = new Tone(stubHmi(false));
        noPopin.connStore.setActiveEntertainmentConnection(40);
        noPopin.connStore.setActiveConnection(40);
        noPopin.soundListener.updateVolume(40, (short) 10, 1);
        fakeNow += 25000;
        noPopin.soundListener.updateVolume(40, (short) 11, 1);
        check("HMI service without popin support: disables cleanly", VolumeBar.isDisabled());

        File log = new File(VolumeBar.logPath);
        check("log file written", log.exists() && log.length() > 0);
    }

    // ------------------------------------------------------------------

    /**
     * Stands in for the real pop-up. Like the real one, the first show goes
     * through the framework (connect), later shows/hides are direct.
     */
    static class FakeUi extends VolumeBar.Ui {
        boolean connected;
        boolean visible;
        int screen = -1;
        boolean camera;

        int screen() {
            return screen;
        }

        boolean rearCamera() {
            return camera;
        }

        boolean ensureInstalled() {
            calls.add("ensureInstalled");
            return true;
        }

        void apply() {
            calls.add("apply");
        }

        boolean showDirect() {
            if (!connected) {
                return false;
            }
            calls.add("showDirect");
            visible = true;
            return true;
        }

        void hide(boolean instant) {
            calls.add(instant ? "hideInstant" : "hideFade");
            if (connected) {
                calls.add("hideDirect");
                visible = false;
            }
        }
    }

    static FakeUi fakeUi() {
        return (FakeUi) VolumeBar.ui;
    }

    static class Tone extends ToneAppBase {
        Tone(HMIService hmi) {
            super(new QuietLogger(), hmi, new ConnectionStore(), new ToneModelBank(), null);
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

    static Tone newTone() {
        return new Tone(stubHmi(true));
    }

    static LogChannel silentChannel() {
        return new QuietLogger().getLogChannel("x");
    }

    static class QuietLogger extends Logger {
        protected LogChannel getLogChannel(String name) {
            return (LogChannel) Proxy.newProxyInstance(VolumeBarHarness.class.getClassLoader(),
                    new Class[] {LogChannel.class}, new InvocationHandler() {
                        public Object invoke(Object p, Method m, Object[] args) {
                            if (m.getReturnType() == int.class) return Integer.valueOf(0);
                            if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                            return null;
                        }
                    });
        }
    }

    static HMIService stubHmi(boolean withPopin) {
        Class[] ifs = withPopin ? new Class[] {HMIService.class, HMIServicePopin.class}
                : new Class[] {HMIService.class};
        return (HMIService) Proxy.newProxyInstance(VolumeBarHarness.class.getClassLoader(), ifs,
                new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] args) {
                        String n = m.getName();
                        if (n.equals("showPopin")) {
                            if (popinThrows) throw new RuntimeException("simulated UI failure");
                            calls.add("show");
                            if (VolumeBar.ui instanceof FakeUi) {
                                fakeUi().connected = true;
                                fakeUi().visible = true;
                            }
                            return null;
                        }
                        if (n.equals("removePopin")) {
                            calls.add("remove");
                            return null;
                        }
                        if (n.equals("toString")) return "StubHMI";
                        if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                        if (m.getReturnType() == int.class) return Integer.valueOf(0);
                        return null;
                    }
                });
    }

    /** Any show: framework (first time) or direct. */
    static int countShow() {
        return count("show") + count("showDirect");
    }

    static int countConnectShow() {
        return count("show");
    }

    /** Any hide. */
    static int countRemove() {
        return count("remove") + count("hideDirect");
    }

    static synchronized int count(String s) {
        int c = 0;
        for (int i = 0; i < calls.size(); i++) if (calls.get(i).equals(s)) c++;
        return c;
    }

    static boolean contains(int[] a, int v) {
        if (a == null) return false;
        for (int i = 0; i < a.length; i++) if (a[i] == v) return true;
        return false;
    }

    static void section(String s) {
        System.out.println();
        System.out.println("== " + s);
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (ok) passed++;
        else failed++;
    }
}
