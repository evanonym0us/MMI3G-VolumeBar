package de.audi.tghu.tone.hmi.evolution;

import de.audi.tghu.hmi.view.LabelWidget;
import de.audi.tghu.tone.app.VolumeBar;

/**
 * Title row of the pop-up: which volume the knob is changing
 * ("Entertainment volume", "Call volume", ...).
 */
public class VolumeTitleLabel extends LabelWidget {

    public VolumeTitleLabel(int x, int y, int w, int h) {
        super(x, y, w, h);
    }

    protected void updateText() {
        try {
            this.text = VolumeBarScreen.titleText();
        } catch (Throwable t) {
            this.text = "";
            VolumeBar.fail("title text", t);
        }
        this.numberOfRows = splitText();
    }

    /**
     * Ignored. Every label registers with the MMI's animation controller,
     * whose screen-change animations fade those labels out and only fade
     * the new screen's labels back in. Ours would stay invisible. The
     * pop-up shows and hides itself, so its labels stay fully opaque.
     */
    public void setOpacity(float f) {
    }

    /** Re-reads the title and repaints if it changed. */
    public void refresh() {
        String before = this.text;
        updateText();
        if (before == null || !before.equals(this.text)) {
            setCompositesDirty(true);
        }
    }
}
