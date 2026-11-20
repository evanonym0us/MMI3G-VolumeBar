package de.audi.tghu.tone.hmi.evolution;

/**
 * Pure geometry for the volume bar drawing. No device classes here, so it
 * can be unit-tested on the computer.
 *
 * Path command bytes are the ones the stock ToolTipWidget uses:
 * 2 = MOVE_TO_ABS, 4 = LINE_TO_ABS, 20 = SCWARC_TO_ABS (rx, ry, rot, x, y),
 * 0 = CLOSE_PATH.
 */
public final class VolumeBarShapes {

    public static final byte MOVE = 2;
    public static final byte LINE = 4;
    public static final byte ARC_CW = 20;
    public static final byte CLOSE = 0;

    /** Same command layout as ToolTipWidget's rounded rectangle. */
    public static final byte[] ROUND_RECT_CMDS = {MOVE, LINE, ARC_CW, LINE, ARC_CW, LINE, ARC_CW, LINE, ARC_CW, CLOSE};
    public static final int ROUND_RECT_COORDS = 30;

    private VolumeBarShapes() {
    }

    /**
     * Rounded rectangle at (x, y), size w x h, corner radius r.
     * r is clamped to half the smaller side, so r = h / 2 gives a pill
     * and w = h with r = w / 2 gives a circle.
     */
    public static float[] roundRect(float x, float y, float w, float h, float r) {
        float max = Math.min(w, h) / 2.0f;
        if (r > max) {
            r = max;
        }
        if (r < 0.0f) {
            r = 0.0f;
        }
        float[] p = new float[ROUND_RECT_COORDS];
        int i = 0;
        // move to top edge start
        p[i++] = x + r; p[i++] = y;
        // top edge
        p[i++] = x + w - r; p[i++] = y;
        // top-right corner
        p[i++] = r; p[i++] = r; p[i++] = 0.0f; p[i++] = x + w; p[i++] = y + r;
        // right edge
        p[i++] = x + w; p[i++] = y + h - r;
        // bottom-right corner
        p[i++] = r; p[i++] = r; p[i++] = 0.0f; p[i++] = x + w - r; p[i++] = y + h;
        // bottom edge
        p[i++] = x + r; p[i++] = y + h;
        // bottom-left corner
        p[i++] = r; p[i++] = r; p[i++] = 0.0f; p[i++] = x; p[i++] = y + h - r;
        // left edge
        p[i++] = x; p[i++] = y + r;
        // top-left corner
        p[i++] = r; p[i++] = r; p[i++] = 0.0f; p[i++] = x + r; p[i++] = y;
        return p;
    }

    /** Speaker body (filled polygon), top-left at (x, y), 15 x 22 px. */
    public static final byte[] SPEAKER_CMDS = {MOVE, LINE, LINE, LINE, LINE, LINE, CLOSE};

    public static float[] speaker(float x, float y) {
        return new float[] {
            x, y + 7,
            x + 6, y + 7,
            x + 15, y,
            x + 15, y + 22,
            x + 6, y + 15,
            x, y + 15
        };
    }

    /**
     * Volume ramp (the 3G+ volume symbol): a wedge rising to the right,
     * 36 x 17 px, top-left at (x, y). Stroked outline.
     */
    public static final byte[] RAMP_CMDS = {MOVE, LINE, LINE, CLOSE};
    public static final float RAMP_W = 36.0f;
    public static final float RAMP_H = 17.0f;

    public static float[] ramp(float x, float y) {
        return new float[] {
            x + 1, y + RAMP_H - 1,
            x + RAMP_W - 1, y + 1,
            x + RAMP_W - 1, y + RAMP_H - 1
        };
    }

    /**
     * The filled part of the ramp for a level: the wedge from its tip up to
     * value/max of its length, so the ramp fills from left to right as the
     * volume rises. Null at 0 (nothing to fill).
     */
    public static final byte[] RAMP_FILL_CMDS = {MOVE, LINE, LINE, CLOSE};

    public static float[] rampFill(float x, float y, int value, int max) {
        if (value <= 0 || max <= 0) {
            return null;
        }
        float f = value >= max ? 1.0f : (float) value / (float) max;
        float x0 = x + 1, x1 = x + RAMP_W - 1;
        float bottom = y + RAMP_H - 1, top = y + 1;
        float xf = x0 + (x1 - x0) * f;
        float yf = bottom - (bottom - top) * f;   // height of the slope at xf
        return new float[] {x0, bottom, xf, yf, xf, bottom};
    }

    /**
     * Mute symbol (as on the 3G+): a speaker crossed by a diagonal slash,
     * in a 38 x 27 px box with top-left at (x, y).
     */
    public static final float MUTE_W = 38.0f;
    public static final float MUTE_H = 27.0f;

    /** Speaker of the mute symbol (use SPEAKER_CMDS). */
    public static float[] muteSpeaker(float x, float y) {
        return speaker(x + 11, y + 2);
    }

    public static final byte[] SLASH_CMDS = {MOVE, LINE};

    public static float[] muteSlash(float x, float y) {
        return new float[] {x + 7, y + MUTE_H - 2, x + 31, y + 2};
    }

    /**
     * Horizontal position of the knob centre for a level, inside a track
     * running from left to right. Clamped to the track.
     */
    public static float knobX(int value, int max, float left, float right) {
        if (max <= 0) {
            return left;
        }
        if (value < 0) {
            value = 0;
        }
        if (value > max) {
            value = max;
        }
        return left + (right - left) * ((float) value / (float) max);
    }
}
