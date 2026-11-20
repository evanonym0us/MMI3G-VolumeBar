package de.audi.tghu.tone.app;

import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.HMIServicePopin;
import de.audi.atip.hmi.model.HMIModel;
import de.audi.atip.hmi.model.RangeModel;
import de.audi.tghu.tone.app.conn.ConnectionStore;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Timer;
import java.util.TimerTask;

/**
 * On-screen volume level for the MMI 3G High (HNav K0133), modelled on the
 * 3G+ volume pop-up (K0942 de.audi.tghu.tone.app.volume.popup).
 *
 * Shows on every turn of the volume knob, including at either end of the
 * scale, and hides 3 s after the last turn, straight away when the knob
 * is pressed, or when the main screen changes. It also shows when the
 * level changes some other way, e.g. from the steering wheel. The title
 * names the volume being changed. Never shown over the rear view camera.
 *
 * The pop-up repaints itself when something on it changes. The stock
 * model-update route can't be used for that: it postpones repaints while
 * any screen animation runs, which froze the bar on some screens.
 *
 * Hooks, each a single call wrapped in try/catch in the stock code:
 *   SoundListener.updateVolume            -> onVolume
 *   MenuVolumeHandler.changeAudibleVolume -> onKnob
 *   MenuVolumeHandler.keyTyped            -> onKnobPress
 *
 * Runs on J9 Foundation 1.0 (class format 46): no StringBuilder, generics
 * or autoboxing. Errors never leave this class; after MAX_ERRORS of them
 * the feature switches itself off until the next restart.
 */
public final class VolumeBar {

    public static final int SCREEN_ID = 100011;   // unused slot in ToneScreenFactory
    public static final int MODEL_ID = 100027;    // unused slot in ToneModelBank

    static final int TERMINAL_FRONT = 0;
    static final long HIDE_AFTER_MS = 3000L;
    static final long WATCH_MS = 200L;   // how often a shown pop-up checks for hiding
    static final long STARTUP_QUIET_MS = 20000L;
    static final int DEFAULT_MAX = 34;   // entertainment volume stops at 34 on the car
    static final int MAX_ERRORS = 5;

    static String logPath = "/tmp/volumebar.log";
    static final long LOG_LIMIT = 65536L;

    private static final Object LOCK = new Object();

    // Pop-up titles, numbered as in the 3G+ firmware
    public static final int T_ENT = 0;
    public static final int T_RING = 1;
    public static final int T_CALL = 2;
    public static final int T_NAV = 4;
    public static final int T_TMC = 5;
    public static final int T_SDS = 6;
    public static final int T_TA = 7;
    static final int TITLES = 8;

    static final int KNOB_MODEL = 100021;   // ToneModelBank.VOLUME_RANGE
    static final int CONN_VOL_LOCK = 5;     // AudioConnection.ENT_VOLUME_LOCK

    private static RangeModel model;
    private static final int[] cache = newCache();   // last level per title
    private static int title = T_ENT;
    private static boolean locked;
    private static int shownMax = DEFAULT_MAX;
    private static long firstEventAt;
    private static int lastConn = -1;
    private static int lastVol = -1;
    private static int max = DEFAULT_MAX;
    private static boolean shown;
    private static Timer timer;
    private static TimerTask watch;
    private static long hideAt;
    private static int screenAtShow = -1;
    private static volatile boolean cameraLogged;
    private static HMIServicePopin popinService;
    private static int errors;
    private static volatile boolean disabled;

    /** Indirection so the desktop harness can run without the real UI. */
    static Ui ui = new Ui();

    static class Ui {
        boolean ensureInstalled() {
            return de.audi.tghu.tone.hmi.evolution.VolumeBarScreen.ensureInstalled();
        }

        /** Shows the pop-up if it is already connected (no screen build). */
        boolean showDirect() {
            final de.audi.tghu.tone.hmi.evolution.VolumeBarPopin p =
                    de.audi.tghu.tone.hmi.evolution.VolumeBarScreen.popin();
            if (p == null || !p.isConnected()) {
                return false;
            }
            de.audi.tghu.fwhmi.EventQueue.invokeLater(new Runnable() {
                public void run() {
                    p.showIfConnected();
                }
            });
            return true;
        }

        private boolean applyQueued;
        private final Runnable applyNow = new Runnable() {
            public void run() {
                synchronized (Ui.this) {
                    applyQueued = false;
                }
                try {
                    de.audi.tghu.tone.hmi.evolution.VolumeBarScreen.apply();
                } catch (Throwable t) {
                    fail("apply", t);
                }
            }
        };

        /**
         * Refreshes and repaints the pop-up on the HMI thread. At most one
         * refresh is queued at a time; it reads the latest state when it runs.
         */
        void apply() {
            synchronized (this) {
                if (applyQueued) {
                    return;
                }
                applyQueued = true;
            }
            de.audi.tghu.fwhmi.EventQueue.invokeLater(applyNow);
        }

        /** Id of the main screen, or -1 if unknown. */
        int screen() {
            return de.audi.tghu.fwhmi.VolumeBarHmi.screen();
        }

        boolean rearCamera() {
            return de.audi.tghu.fwhmi.VolumeBarHmi.rearCamera();
        }

        /**
         * Hides the pop-up but keeps it connected: a fade, or at once
         * (rear camera, screen change).
         */
        void hide(final boolean instant) {
            final de.audi.tghu.tone.hmi.evolution.VolumeBarPopin p =
                    de.audi.tghu.tone.hmi.evolution.VolumeBarScreen.popin();
            if (p == null || !p.isConnected()) {
                return;
            }
            de.audi.tghu.fwhmi.EventQueue.invokeLater(new Runnable() {
                public void run() {
                    p.hideKeepConnected(instant);
                }
            });
        }
    }

    /** Replaceable so the desktop tests can control time. */
    static Clock clock = new Clock();

    static class Clock {
        long now() {
            return System.currentTimeMillis();
        }
    }

    private VolumeBar() {
    }

    // ---- entry points -----------------------------------------------

    /** Called from the ToneModelBank constructor. Must never return null. */
    public static HMIModel createModel() {
        RangeModel m = new RangeModel(MODEL_ID);
        try {
            m.setLimits(0, max, 1);
        } catch (Throwable t) {
            // limits are re-applied on the first volume event
        }
        synchronized (LOCK) {
            model = m;
        }
        log("model created id=" + MODEL_ID);
        return m;
    }

    /** Called from SoundListener.updateVolume(conn, vol, valid). */
    public static void onVolume(ToneAppBase tone, int conn, int vol, int valid) {
        if (disabled) {
            return;
        }
        try {
            handle(tone, conn, vol, valid);
        } catch (Throwable t) {
            fail("onVolume", t);
        }
    }

    /**
     * Called from MenuVolumeHandler.changeAudibleVolume: the knob was turned.
     * The new level arrives separately through onVolume.
     */
    public static void onKnob(ToneAppBase tone, int steps) {
        if (disabled) {
            return;
        }
        try {
            knob(tone);
        } catch (Throwable t) {
            fail("onKnob", t);
        }
    }

    /** Called from MenuVolumeHandler.keyTyped: pressing the knob hides the pop-up. */
    public static void onKnobPress(ToneAppBase tone, int modelId) {
        if (disabled || modelId != KNOB_MODEL) {
            return;
        }
        try {
            hideNow(false);
        } catch (Throwable t) {
            fail("onKnobPress", t);
        }
    }

    // ---- state ------------------------------------------------------

    /** Which volume a connection belongs to (as in MenuVolumeHandler.updateVolumeModels). */
    static int titleOf(int conn) {
        switch (conn) {
            case 83: case 116: case 117:
                return T_NAV;
            case 86: case 112: case 113:
                return T_SDS;
            case 87: case 98: case 103:
                return T_RING;
            case 90: case 97: case 99: case 100: case 101: case 102:
            case 104: case 105: case 106: case 107: case 110: case 111:
                return T_CALL;
            case 84: case 31: case 32: case 33: case 34:
                return T_TA;
            case 114: case 115: case 118: case 119:
                return T_TMC;
            default:
                return T_ENT;
        }
    }

    // Title, then its connections; in the 3G+ priority order.
    static final int[][] FOREGROUND = {
        {T_NAV, 116, 117},
        {T_TA, 31, 32, 33, 34},
        {T_SDS, 112, 113},
        {T_RING, 98, 103},
        {T_CALL, 97, 99, 100, 101, 102, 104, 105, 106, 107, 110, 111},
        {T_TMC, 114, 115, 118, 119},
    };

    /** The volume the knob changes: that of the audible connection, else entertainment. */
    static int foreground(ConnectionStore cs) {
        for (int i = 0; i < FOREGROUND.length; i++) {
            int[] g = FOREGROUND[i];
            for (int j = 1; j < g.length; j++) {
                if (cs.hasFocus(g[j])) {
                    return g[0];
                }
            }
        }
        return T_ENT;
    }

    static void handle(ToneAppBase tone, int conn, int vol, int valid) {
        if (tone == null || valid != 1) {
            return;
        }
        long now = clock.now();
        boolean show;
        boolean refresh;
        RangeModel m;
        int value, top;
        synchronized (LOCK) {
            if (firstEventAt == 0L) {
                firstEventAt = now;
            }
            int t = titleOf(conn);
            int ent = tone.connStore.getActiveEntertainmentConnection();
            boolean focus = tone.connStore.hasFocus(conn);
            if (t != T_ENT && vol >= 0) {
                cache[t] = vol;   // nav, phone etc.: one volume each, whatever the connection
            }
            // also follow the volume on screen, e.g. a nav prompt that has ended
            boolean relevant = conn == ent || focus || (shown && t == title && t != T_ENT);
            if (!relevant) {
                return;
            }
            if (t == T_ENT) {
                // Only the source you are listening to: background and
                // unknown connections must not overwrite the level.
                if (vol >= 0) {
                    cache[T_ENT] = vol;
                }
                if (vol > max) {
                    max = vol;
                    log("max raised to " + max);
                }
            }
            m = model;
            if (m == null) {
                log("no model yet");
                return;
            }
            boolean sameSource = conn == lastConn;
            boolean changed = vol != lastVol;
            boolean quiet = now - firstEventAt < STARTUP_QUIET_MS;
            lastConn = conn;
            lastVol = vol;
            refresh = setState(tone, t);
            value = shownValue();
            top = updateTop(tone);
            // The first report for a source is its starting level, not a change.
            show = sameSource && changed && !quiet;
        }
        if (push(m, value, top) || refresh) {
            refreshIfShown();
        }
        if (show) {
            showNow(tone);
        }
    }

    static void knob(ToneAppBase tone) {
        if (tone == null) {
            return;
        }
        RangeModel m;
        boolean refresh;
        int value, top;
        synchronized (LOCK) {
            m = model;
            if (m == null) {
                return;
            }
            if (firstEventAt == 0L) {
                firstEventAt = clock.now();
            }
            refresh = setState(tone, foreground(tone.connStore));
            value = shownValue();
            top = updateTop(tone);
        }
        if (push(m, value, top) || refresh) {
            refreshIfShown();
        }
        showNow(tone);
    }

    /**
     * Keeps the level model in step, only where something changed (each
     * RangeModel call fires an update, even with the same numbers).
     * Returns true if the level or the scale changed.
     */
    private static boolean push(RangeModel m, int value, int top) {
        boolean changed = false;
        if (m.getMaximum() != top || m.getMinimum() != 0) {
            m.setLimits(0, top, 1);
            changed = true;
        }
        if (m.getValue() != value) {
            m.setValue(value);
            changed = true;
        }
        return changed;
    }

    private static void refreshIfShown() {
        boolean up;
        synchronized (LOCK) {
            up = shown;
        }
        if (up) {
            ui.apply();
        }
    }

    /** Sets title and locked; true if either changed. */
    private static boolean setState(ToneAppBase tone, int t) {
        boolean lo = t == T_ENT && tone.connStore.hasFocus(CONN_VOL_LOCK);
        boolean changed = t != title || lo != locked;
        title = t;
        locked = lo;
        return changed;
    }

    /** The level on screen. */
    private static int shownValue() {
        int v = cache[title];
        return v < 0 ? 0 : v;
    }

    /** Works out the top of the scale for the current title. */
    private static int updateTop(ToneAppBase tone) {
        int top = DEFAULT_MAX;
        if (title == T_ENT) {
            top = max;
        } else {
            int r = rangeMax(tone, title);
            if (r > 0 && r <= 100) {
                top = r;
            }
        }
        int v = cache[title];
        if (v > top) {
            top = v;
        }
        shownMax = top;
        return top;
    }

    /** The maximum the volume menu received from the sound system, or -1. */
    private static int rangeMax(ToneAppBase tone, int t) {
        try {
            ToneModelBank b = tone.modelBank;
            de.audi.atip.hmi.modelaccess.RangeModelApp r;
            switch (t) {
                case T_NAV: r = b.volumeNavRange; break;
                case T_SDS: r = b.volumeSDSRange; break;
                case T_RING: r = b.volumeTelRange; break;
                case T_CALL: r = b.volumeTelCallRange; break;
                case T_TA: case T_TMC: r = b.volumeTPRange; break;
                default: return -1;
            }
            return r == null ? -1 : r.getMaximum();
        } catch (Throwable e) {
            return -1;
        }
    }

    static void hideNow(boolean instant) {
        synchronized (LOCK) {
            if (!shown) {
                return;
            }
            shown = false;
            stopWatch();
        }
        ui.hide(instant);
    }

    private static int[] newCache() {
        int[] c = new int[TITLES];
        for (int i = 0; i < c.length; i++) {
            c[i] = -1;
        }
        return c;
    }

    static void showNow(ToneAppBase tone) {
        if (ui.rearCamera()) {
            if (!cameraLogged) {
                cameraLogged = true;
                log("rear camera on, not showing");
            }
            hideNow(true);
            return;
        }
        cameraLogged = false;
        int screenNow = ui.screen();
        synchronized (LOCK) {
            if (shown) {
                // Already up: keep it up. A screen change while you are
                // still turning doesn't take it away.
                hideAt = System.currentTimeMillis() + HIDE_AFTER_MS;
                if (screenNow != -1) {
                    screenAtShow = screenNow;
                }
                return;
            }
        }
        HMIServicePopin svc = popinService(tone);
        if (svc == null) {
            return;
        }
        if (!ui.ensureInstalled()) {
            log("popin screen not available, volume bar disabled");
            disabled = true;
            return;
        }
        boolean needShow;
        String state;
        synchronized (LOCK) {
            needShow = !shown;
            shown = true;
            screenAtShow = screenNow;
            startWatch();
            state = "title=" + title + " level=" + shownValue() + " max=" + shownMax + " screen=" + screenNow;
        }
        if (needShow) {
            try {
                // the pop-up brings its widgets up to date as it appears
                if (!ui.showDirect()) {
                    log("show (connect) " + state);
                    svc.showPopin(TERMINAL_FRONT, SCREEN_ID);
                }
            } catch (Throwable t) {
                synchronized (LOCK) {
                    shown = false;
                    stopWatch();
                }
                fail("showPopin", t);
            }
        }
    }

    /**
     * While shown, a task checks every WATCH_MS whether to hide: 3 s after
     * the last change, when the main screen has changed, or when the rear
     * view camera comes on. Further turns only move hideAt. Call with LOCK
     * held.
     */
    private static void startWatch() {
        hideAt = System.currentTimeMillis() + HIDE_AFTER_MS;
        if (watch != null) {
            return;
        }
        watch = new TimerTask() {
            public void run() {
                check(this);
            }
        };
        timer().schedule(watch, WATCH_MS, WATCH_MS);
    }

    /** The one timer thread for the hide watch and the fade. */
    public static synchronized Timer timer() {
        if (timer == null) {
            timer = new Timer(true);
        }
        return timer;
    }

    /** Call with LOCK held. */
    private static void stopWatch() {
        if (watch != null) {
            watch.cancel();
            watch = null;
        }
    }

    static final int STAY = 0, FADE_OUT = 1, GO_NOW = 2;

    static void check(TimerTask from) {
        try {
            int screenNow = ui.screen();
            int was = screenAtShow;
            int how = dueToHide(from, screenNow, ui.rearCamera());
            if (how != STAY) {
                ui.hide(how == GO_NOW);
                if (how == GO_NOW && screenNow != was) {
                    log("screen changed " + was + " -> " + screenNow + ", hiding");
                }
            }
        } catch (Throwable t) {
            fail("hide", t);
        }
    }

    /**
     * Decides and records the hide. The camera and a screen change take the
     * pop-up away at once: the camera view must be clear, and a screen
     * change plays its own transition, which a second animation would fight.
     */
    private static int dueToHide(TimerTask from, int screenNow, boolean camera) {
        synchronized (LOCK) {
            if (from != watch || !shown) {
                return STAY;
            }
            boolean timeUp = System.currentTimeMillis() >= hideAt;
            boolean moved = screenAtShow != -1 && screenNow != -1 && screenNow != screenAtShow;
            if (!timeUp && !moved && !camera) {
                return STAY;
            }
            shown = false;
            stopWatch();
            return (moved || camera) ? GO_NOW : FADE_OUT;
        }
    }

    private static HMIServicePopin popinService(ToneAppBase tone) {
        if (popinService != null) {
            return popinService;
        }
        HMIService s = tone.hmiService;
        if (s instanceof HMIServicePopin) {
            popinService = (HMIServicePopin) s;
            return popinService;
        }
        log("hmiService has no popin support: " + s);
        disabled = true;
        return null;
    }

    /** Called by VolumeBarScreen if the pop-up can't be built: nothing to show, stop. */
    public static void screenFailed(Throwable t) {
        fail("screen", t);
        disabled = true;
    }

    // ---- errors and logging -------------------------------------------

    public static void fail(String where, Throwable t) {
        errors++;
        log("ERROR in " + where + ": " + t + "\n" + stack(t));
        if (errors >= MAX_ERRORS) {
            disabled = true;
            log("too many errors, volume bar disabled until restart");
        }
    }

    static String stack(Throwable t) {
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            java.io.PrintStream p = new java.io.PrintStream(b);
            t.printStackTrace(p);
            p.flush();
            String s = b.toString();
            return s.length() > 2000 ? s.substring(0, 2000) : s;
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static synchronized void log(String msg) {
        FileOutputStream out = null;
        try {
            File f = new File(logPath);
            if (f.exists() && f.length() > LOG_LIMIT) {
                f.delete();
            }
            out = new FileOutputStream(logPath, true);
            String line = clock.now() + " " + msg + "\n";
            out.write(line.getBytes());
        } catch (Throwable ignored) {
            // logging must never break anything
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---- read by the widgets ------------------------------------------

    public static boolean isDisabled() {
        return disabled;
    }

    public static int level() {
        synchronized (LOCK) {
            return shownValue();
        }
    }

    public static int levelMax() {
        synchronized (LOCK) {
            return shownMax;
        }
    }

    public static int title() {
        synchronized (LOCK) {
            return title;
        }
    }

    /** Volume locked: nothing can be adjusted. */
    public static boolean locked() {
        synchronized (LOCK) {
            return locked;
        }
    }

    /** Entertainment at 0 shows the mute symbol instead of a number. */
    public static boolean showMute() {
        synchronized (LOCK) {
            return title == T_ENT && shownValue() == 0;
        }
    }

    // ---- tests only ---------------------------------------------------

    static void resetForTest() {
        synchronized (LOCK) {
            model = null;
            firstEventAt = 0L;
            lastConn = -1;
            lastVol = -1;
            max = DEFAULT_MAX;
            shownMax = DEFAULT_MAX;
            for (int i = 0; i < cache.length; i++) {
                cache[i] = -1;
            }
            title = T_ENT;
            locked = false;
            hideAt = 0L;
            screenAtShow = -1;
            shown = false;
            stopWatch();
            popinService = null;
            errors = 0;
            disabled = false;
        }
    }

    static boolean isShownForTest() {
        synchronized (LOCK) {
            return shown;
        }
    }

    static int maxForTest() {
        return max;
    }
}
