package de.audi.tghu.tone.hmi.evolution;

import de.audi.tghu.hmi.view.LabelWidget;
import de.audi.tghu.tone.app.VolumeBar;

/**
 * The level as a number, or "Max" at the top of the scale. Blank when
 * entertainment is at 0, where VolumeLevelWidget draws the mute symbol.
 */
public class VolumeNumberLabel extends LabelWidget {

    static final String MAX_TEXT = "Max";

    public VolumeNumberLabel(int x, int y, int w, int h) {
        super(x, y, w, h);
    }

    protected void updateText() {
        try {
            this.text = textFor(VolumeBar.level(), VolumeBar.levelMax(), VolumeBar.showMute());
        } catch (Throwable t) {
            this.text = "";
            VolumeBar.fail("number label", t);
        }
        this.numberOfRows = splitText();
    }

    /** "" where the mute symbol goes, "Max" at the top, otherwise the number. */
    public static String textFor(int value, int max, boolean mute) {
        if (mute) {
            return "";
        }
        if (max > 0 && value >= max) {
            return MAX_TEXT;
        }
        return Integer.toString(value);
    }

    /**
     * Ignored. Every label registers with the MMI's animation controller,
     * whose screen-change animations fade those labels out and only fade
     * the new screen's labels back in. Ours would stay invisible. The
     * pop-up shows and hides itself, so its labels stay fully opaque.
     */
    public void setOpacity(float f) {
    }

    /** Re-reads the text and repaints if it changed. */
    public void refresh() {
        String before = this.text;
        updateText();
        if (before == null || !before.equals(this.text)) {
            setCompositesDirty(true);
        }
    }
}
