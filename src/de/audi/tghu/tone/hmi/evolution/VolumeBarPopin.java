package de.audi.tghu.tone.hmi.evolution;

import de.audi.atip.hmi.view.PopinListener;
import de.audi.tghu.hmi.view.RedrawContext;
import de.audi.tghu.hmi.view.ScreenWidgetPopin;
import de.audi.tghu.tone.app.VolumeBar;
import fi.hybrid.a2dg.Graphics2D;
import fi.hybrid.a2dg.Path;
import fi.hybrid.a2dg.SolidPaint;
import fi.hybrid.hwg.Bitmap;

import java.util.TimerTask;

/**
 * The pop-up container: a dark rounded panel drawn in code.
 *
 * Unlike the climate pop-in it extends, it fades in and out instead of
 * sliding, and it stays connected when hidden so the next show is only a
 * fade. A fade frame just changes the container's opacity, which the
 * compositor applies without redrawing anything.
 *
 * Every override catches its own errors so nothing reaches the framework.
 */
public class VolumeBarPopin extends ScreenWidgetPopin {

    static final int PANEL_FILL = 0xFF0A0A0A;
    static final int PANEL_EDGE = 0xFF8C8C8C;
    static final float PANEL_RADIUS = 8.0f;

    private Bitmap panel;
    private boolean visible;   // on screen, possibly still fading

    // The fade is only touched on the HMI thread; the ticker thread just
    // queues the next frame there.
    private final VolumeBarFade fade = new VolumeBarFade(0.0f);
    private TimerTask tick;
    private final Object frameLock = new Object();
    private boolean framePending;
    private final Runnable frame = new Runnable() {
        public void run() {
            synchronized (frameLock) {
                framePending = false;
                if (tick == null) {
                    return;   // fade already finished or stopped
                }
            }
            try {
                nextFrame();
            } catch (Throwable t) {
                stopTicker();
                VolumeBar.fail("fade", t);
                try {
                    finishFade();   // never leave it half transparent
                    triggerRepaint();
                } catch (Throwable ignored) {
                }
            }
        }
    };

    public VolumeBarPopin(int id, int x, int y, int w, int h) {
        super(id, x, y, w, h);
    }

    /** Bitmap 0 is the background, drawn once and reused. */
    protected Bitmap getBitmap(int i) {
        if (i == 0) {
            try {
                if (panel == null) {
                    panel = drawPanel();
                }
                return panel;
            } catch (Throwable t) {
                VolumeBar.fail("panel", t);
            }
        }
        return super.getBitmap(i);
    }

    private Bitmap drawPanel() {
        Bitmap b = this.terminal.getHWGManager().getBitmap(Bitmap.RGBA, this.width + 1, this.height + 1);
        Graphics2D g = Graphics2D.getInstance();
        Path box = null;
        try {
            g.getTransform().translate(0.5f, 0.5f);
            g.bindTarget(b);
            g.clear(0);

            box = path(VolumeBarShapes.ROUND_RECT_CMDS,
                    VolumeBarShapes.roundRect(0, 0, this.width - 1, this.height - 1, PANEL_RADIUS));
            g.setFill(new SolidPaint(PANEL_FILL));   // drawn once, so no need to cache the paints
            g.setStroke(new SolidPaint(PANEL_EDGE));
            g.setStrokeWidth(1.0f);
            g.fillAndStrokePath(box);
        } finally {
            try {
                g.releaseTarget();
            } catch (Throwable ignored) {
            }
            g.getTransform().setIdentity();
            dispose(box);
        }
        return b;
    }

    static Path path(byte[] cmds, float[] coords) {
        Path p = new Path(cmds.length, coords.length);
        p.append(cmds, coords);
        return p;
    }

    static void dispose(Path p) {
        if (p != null) {
            try {
                p.dispose();
            } catch (Throwable ignored) {
            }
        }
    }

    public void render(RedrawContext rc) {
        try {
            super.render(rc);
            // a container created by this render starts opaque
            opacity(fade.value());
        } catch (Throwable t) {
            VolumeBar.fail("render", t);
        }
    }

    // ---- show / hide ----------------------------------------------------

    /** First show, by the framework when it connects the pop-up. */
    public void showPopin(PopinListener listener, boolean animated) {
        VolumeBar.log("pop-up connected");
        try {
            VolumeBarScreen.refresh(true);   // the fade's first frame paints it
            fadeTo(1.0f);
        } catch (Throwable t) {
            VolumeBar.fail("show", t);
        }
        if (listener != null) {
            listener.showAnimationFinished(this.id);
        }
    }

    /** Hide requested by the framework: immediate. */
    public void hidePopin(PopinListener listener, boolean animated) {
        VolumeBar.log("pop-up hidden by the MMI");
        try {
            stopTicker();
            fade.set(0.0f);
            place(false);
            triggerRepaint();
        } catch (Throwable t) {
            VolumeBar.fail("hide", t);
        }
        if (listener != null) {
            listener.hideAnimationFinished(this.id);
        }
    }

    /** Fades out, or goes at once, but stays connected. */
    public void hideKeepConnected(boolean instant) {
        try {
            if (!isConnected() || !visible) {
                return;
            }
            if (instant) {
                stopTicker();
                fade.set(0.0f);
                opacity(0.0f);
                place(false);
                triggerRepaint();
            } else {
                fadeTo(0.0f);
            }
        } catch (Throwable t) {
            VolumeBar.fail("hideKeepConnected", t);
        }
    }

    /** Fades in again without going through the framework. */
    public boolean showIfConnected() {
        try {
            if (isConnected()) {
                VolumeBarScreen.refresh(true);   // the fade's first frame paints it
                fadeTo(1.0f);
                return true;
            }
        } catch (Throwable t) {
            VolumeBar.fail("showIfConnected", t);
        }
        return false;
    }

    private void fadeTo(float target) {
        long now = System.currentTimeMillis();
        if (target > 0.0f && !visible) {
            opacity(fade.value());   // move it on screen while still transparent
            place(true);
        }
        fade.start(target, now);
        frameAt(now);
        if (fade.isRunning()) {
            startTicker();
        }
    }

    private void nextFrame() {
        frameAt(System.currentTimeMillis());
    }

    private void frameAt(long now) {
        if (!isConnected()) {
            stopTicker();   // taken down by the framework mid-fade
            return;
        }
        float a = fade.step(now);
        opacity(a);
        if (!fade.isRunning()) {
            stopTicker();
            if (a <= 0.0f) {
                place(false);
            }
        }
        triggerRepaint();
    }

    /**
     * Makes sure the opacity on screen is the fade's: finishes a fade whose
     * frames stopped coming, and re-applies the value in case anything else
     * changed the container's opacity. HMI thread.
     */
    public void settleOpacity() {
        boolean stalled;
        synchronized (frameLock) {
            stalled = fade.isRunning() && tick == null;
        }
        if (stalled) {
            finishFade();
        } else {
            opacity(fade.value());
        }
    }

    private void finishFade() {
        fade.set(fade.target());
        opacity(fade.value());
        if (fade.value() <= 0.0f) {
            place(false);
        }
    }

    public void disconnecting() {
        VolumeBar.log("pop-up disconnected by the MMI");
        stopTicker();
        fade.set(0.0f);
        visible = false;
        super.disconnecting();
    }

    private void opacity(float a) {
        if (this.composites != null && this.composites[0] != null) {
            // children live inside the container and follow it
            this.composites[0].setOpacity(a);
        }
    }

    // The ticker shares VolumeBar's timer thread; it uses frameLock, never
    // this widget's monitor, so it can't block on the framework.
    private void startTicker() {
        synchronized (frameLock) {
            if (tick != null) {
                return;
            }
            tick = new TimerTask() {
                public void run() {
                    try {
                        synchronized (frameLock) {
                            if (framePending) {
                                return;   // don't pile up frames behind a busy HMI thread
                            }
                            framePending = true;
                        }
                        de.audi.tghu.fwhmi.EventQueue.invokeLater(frame);
                    } catch (Throwable t) {
                        synchronized (frameLock) {   // never let the shared timer thread die
                            framePending = false;
                            if (tick == this) {
                                tick = null;
                            }
                        }
                        cancel();
                    }
                }
            };
            VolumeBar.timer().schedule(tick, VolumeBarFade.FRAME_MS, VolumeBarFade.FRAME_MS);
        }
    }

    private void stopTicker() {
        synchronized (frameLock) {
            if (tick != null) {
                tick.cancel();
                tick = null;
            }
        }
    }

    /** Moves the pop-up on or off screen. The caller repaints. */
    private void place(boolean show) {
        visible = show;
        if (this.composites == null || this.composites[0] == null) {
            return;
        }
        int px = show ? this.x : -(this.width + 100);
        this.composites[0].setPosition(px, this.y);
    }
}
