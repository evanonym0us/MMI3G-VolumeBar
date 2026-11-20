package de.audi.tghu.tone.hmi.evolution;

import de.audi.tghu.hmi.view.AbstractWidget;
import de.audi.tghu.hmi.view.RedrawContext;
import de.audi.tghu.tone.app.VolumeBar;
import fi.hybrid.a2dg.Graphics2D;
import fi.hybrid.a2dg.Path;
import fi.hybrid.a2dg.SolidPaint;
import fi.hybrid.hwg.Bitmap;
import fi.hybrid.hwg.Composite;

/**
 * The level row of the pop-up:
 *
 *   [ramp]  [===== track, fill and knob =====]  [number / mute]
 *    36   15              245                15      66
 *
 * The ramp fills from left to right with the level. The number is a
 * separate label over the last slot; this widget draws the mute symbol
 * there when entertainment is at 0. Everything is dimmed while the volume
 * is unavailable.
 *
 * Drawn into one texture, redrawn only when something on it changes.
 */
public class VolumeLevelWidget extends AbstractWidget {

    // layout inside the widget, which is 377 x 40
    static final float RAMP_X = 0.0f;
    static final float TRACK_LEFT = VolumeBarShapes.RAMP_W + 15.0f;   // 51
    static final float TRACK_W = 245.0f;
    public static final float SLOT_X = TRACK_LEFT + TRACK_W + 15.0f;  // 311
    public static final float SLOT_W = 66.0f;                         // room for "Max"
    public static final int WIDTH = (int) (SLOT_X + SLOT_W);          // 377

    static final float TRACK_H = 8.0f;
    static final float KNOB_D = 18.0f;

    // normal / unavailable
    static final int[] TRACK = {0xFF4A4A4A, 0xFF2E2E2E};
    static final int[] FILL = {0xFFDCDCDC, 0xFF5A5A5A};
    static final int[] KNOB = {0xFFBEBEBE, 0xFF5A5A5A};
    static final int[] RIM = {0xFFF2F2F2, 0xFF6E6E6E};
    static final int[] ICON = {0xFFFFFFFF, 0xFF6E6E6E};
    static final int SLASH_GAP = VolumeBarPopin.PANEL_FILL;

    // native resources: created once and kept
    private static SolidPaint[][] paints;
    private static SolidPaint gapPaint;

    private static final int P_TRACK = 0, P_FILL = 1, P_KNOB = 2, P_RIM = 3, P_ICON = 4;

    private static void initPaints() {
        if (paints == null) {
            SolidPaint[][] p = new SolidPaint[2][];
            for (int i = 0; i < 2; i++) {
                p[i] = new SolidPaint[] {
                    new SolidPaint(TRACK[i]), new SolidPaint(FILL[i]), new SolidPaint(KNOB[i]),
                    new SolidPaint(RIM[i]), new SolidPaint(ICON[i])
                };
            }
            gapPaint = new SolidPaint(SLASH_GAP);
            paints = p;
        }
    }

    private Bitmap tex;
    private Composite shownIn;
    private int drawnKey = -1;

    public VolumeLevelWidget(int x, int y, int w, int h) {
        super();
        setBounds(x, y, w, h);
    }

    protected int getCompositeCount() {
        return 1;
    }

    /** Everything the texture depends on, packed into one int. */
    static int key(int value, int max, boolean mute, boolean locked) {
        return ((value & 0xFF) << 16) | ((max & 0xFF) << 8) | (mute ? 2 : 0) | (locked ? 1 : 0);
    }

    public void render(RedrawContext rc) {
        if (VolumeBar.isDisabled()) {
            return;
        }
        try {
            Composite c = initializeDefaultComposite(rc, 50);
            if (tex == null) {
                tex = this.terminal.getHWGManager().getBitmap(Bitmap.RGBA, this.width + 1, this.height + 1);
            }
            int v = VolumeBar.level();
            int m = VolumeBar.levelMax();
            boolean mute = VolumeBar.showMute();
            boolean locked = VolumeBar.locked();
            int k = key(v, m, mute, locked);
            boolean redrawn = false;
            if (k != drawnKey) {
                draw(v, m, mute, locked);
                drawnKey = k;
                redrawn = true;
            }
            if (redrawn || c != shownIn) {
                // re-attach after a redraw, as the stock widgets do
                c.clearDrawables();
                c.addDrawable(tex, 0);
                shownIn = c;
            }
            if (this.compositeDirty != null && this.compositeDirty.length > 0) {
                this.compositeDirty[0] = false;
            }
        } catch (Throwable t) {
            VolumeBar.fail("level render", t);
        }
    }

    public void disconnecting() {
        shownIn = null;
        super.disconnecting();
    }

    private void draw(int value, int max, boolean mute, boolean locked) {
        float r = KNOB_D / 2.0f;
        float left = TRACK_LEFT + r;
        float right = TRACK_LEFT + TRACK_W - r;
        float cy = this.height / 2.0f;
        float kx = VolumeBarShapes.knobX(value, max, left, right);

        initPaints();
        SolidPaint[] p = paints[locked ? 1 : 0];
        Graphics2D g = Graphics2D.getInstance();
        Path ramp = null;
        Path rampFill = null;
        Path track = null;
        Path fill = null;
        Path knob = null;
        Path rim = null;
        Path spk = null;
        Path slash = null;
        try {
            g.bindTarget(tex);
            g.clear(0);

            // ramp, filled up to the level
            float ry = cy - VolumeBarShapes.RAMP_H / 2.0f;
            float[] rf = VolumeBarShapes.rampFill(RAMP_X, ry, value, max);
            if (rf != null) {
                rampFill = VolumeBarPopin.path(VolumeBarShapes.RAMP_FILL_CMDS, rf);
                g.setFill(p[P_ICON]);
                g.fillPath(rampFill);
            }
            ramp = VolumeBarPopin.path(VolumeBarShapes.RAMP_CMDS, VolumeBarShapes.ramp(RAMP_X, ry));
            g.setStroke(p[P_ICON]);
            g.setStrokeWidth(2.0f);
            g.strokePath(ramp);

            // track, fill, knob
            track = VolumeBarPopin.path(VolumeBarShapes.ROUND_RECT_CMDS,
                    VolumeBarShapes.roundRect(TRACK_LEFT, cy - TRACK_H / 2, TRACK_W, TRACK_H, TRACK_H / 2));
            g.setFill(p[P_TRACK]);
            g.fillPath(track);
            if (kx > TRACK_LEFT + 1.0f) {
                fill = VolumeBarPopin.path(VolumeBarShapes.ROUND_RECT_CMDS,
                        VolumeBarShapes.roundRect(TRACK_LEFT, cy - TRACK_H / 2, kx - TRACK_LEFT, TRACK_H, TRACK_H / 2));
                g.setFill(p[P_FILL]);
                g.fillPath(fill);
            }
            rim = VolumeBarPopin.path(VolumeBarShapes.ROUND_RECT_CMDS,
                    VolumeBarShapes.roundRect(kx - r, cy - r, KNOB_D, KNOB_D, r));
            g.setFill(p[P_RIM]);
            g.fillPath(rim);
            knob = VolumeBarPopin.path(VolumeBarShapes.ROUND_RECT_CMDS,
                    VolumeBarShapes.roundRect(kx - r + 1.5f, cy - r + 1.5f, KNOB_D - 3.0f, KNOB_D - 3.0f, r));
            g.setFill(p[P_KNOB]);
            g.fillPath(knob);

            // mute symbol in the number slot
            if (mute) {
                float mx = SLOT_X + SLOT_W - VolumeBarShapes.MUTE_W;   // right-aligned, like the number
                float my = cy - VolumeBarShapes.MUTE_H / 2.0f;
                spk = VolumeBarPopin.path(VolumeBarShapes.SPEAKER_CMDS, VolumeBarShapes.muteSpeaker(mx, my));
                g.setFill(p[P_ICON]);
                g.fillPath(spk);
                slash = VolumeBarPopin.path(VolumeBarShapes.SLASH_CMDS, VolumeBarShapes.muteSlash(mx, my));
                g.setStroke(gapPaint);
                g.setStrokeWidth(6.0f);
                g.strokePath(slash);
                g.setStroke(p[P_ICON]);
                g.setStrokeWidth(2.0f);
                g.strokePath(slash);
            }
        } finally {
            try {
                g.releaseTarget();
            } catch (Throwable ignored) {
            }
            VolumeBarPopin.dispose(ramp);
            VolumeBarPopin.dispose(rampFill);
            VolumeBarPopin.dispose(track);
            VolumeBarPopin.dispose(fill);
            VolumeBarPopin.dispose(knob);
            VolumeBarPopin.dispose(rim);
            VolumeBarPopin.dispose(spk);
            VolumeBarPopin.dispose(slash);
        }
    }
}
