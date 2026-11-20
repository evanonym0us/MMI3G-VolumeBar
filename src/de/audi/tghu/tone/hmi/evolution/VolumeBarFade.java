package de.audi.tghu.tone.hmi.evolution;

/**
 * Opacity over time for the pop-up fade. The 3G+ fades its pop-ups over
 * 250 ms; this one is a little quicker coming in so it feels immediate.
 *
 * Values come from the clock rather than a frame count, so a slow frame
 * never stretches the fade. No device classes: tested on the computer.
 */
public final class VolumeBarFade {

    public static final long IN_MS = 150L;
    public static final long OUT_MS = 220L;
    public static final long FRAME_MS = 40L;   // the 3G High animation minimum delay

    private float from;
    private float to;
    private long start;
    private long duration;
    private boolean running;
    private float value;

    public VolumeBarFade(float initial) {
        this.value = initial;
        this.from = initial;
        this.to = initial;
    }

    /**
     * Starts (or redirects) a fade towards target, from the current value.
     * The clock starts one frame in, so the frame painted straight away
     * already shows a change instead of repeating the old picture.
     */
    public void start(float target, long now) {
        if (target == to && running) {
            return;
        }
        from = value;
        to = target;
        float span = Math.abs(to - from);
        long full = to > from ? IN_MS : OUT_MS;
        duration = (long) (full * span);
        start = now - Math.min(FRAME_MS, duration / 2);
        running = duration > 0L;
        if (!running) {
            value = to;
        }
    }

    /** Advances to time now; returns the opacity to show. */
    public float step(long now) {
        if (!running) {
            return value;
        }
        long t = now - start;
        if (t >= duration) {
            value = to;
            running = false;
            return value;
        }
        if (t < 0L) {
            t = 0L;
        }
        float p = (float) t / (float) duration;
        float e = to > from ? easeOut(p) : easeInOut(p);
        value = from + (to - from) * e;
        return value;
    }

    /** Fast start, soft landing: the pop-up is mostly visible after one frame. */
    static float easeOut(float p) {
        float q = 1.0f - p;
        return 1.0f - q * q * q;
    }

    /** Smoothstep: no visible jump at either end. */
    static float easeInOut(float p) {
        return p * p * (3.0f - 2.0f * p);
    }

    public float target() {
        return to;
    }

    public boolean isRunning() {
        return running;
    }

    public float value() {
        return value;
    }

    /** Jumps straight to v (no animation). */
    public void set(float v) {
        value = v;
        from = v;
        to = v;
        running = false;
    }
}
