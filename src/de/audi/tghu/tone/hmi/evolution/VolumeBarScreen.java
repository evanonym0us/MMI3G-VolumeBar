package de.audi.tghu.tone.hmi.evolution;

import de.audi.atip.hmi.view.Screen;
import de.audi.atip.hmi.view.VolumeBarSlot;
import de.audi.tghu.tone.app.VolumeBar;
import fi.hybrid.a2dg.SolidPaint;

/**
 * Layout of the volume pop-up, after the 3G+ one (K0942 SystemScreenFactory,
 * partialPopupVolume), in 3G High colours:
 *
 *   +---------------------------------------------+
 *   |            Entertainment volume             |
 *   |  /|  ==========O----------------       17   |
 *   +---------------------------------------------+
 *
 * The panel sits in the empty band above the bottom of the frame, clear
 * of the clock line (y 422). Screen is 800 x 480.
 */
public final class VolumeBarScreen {

    static final int PAD = 24;
    static final int W = PAD + VolumeLevelWidget.WIDTH + PAD;   // 425
    static final int H = 86;
    static final int X = (800 - W) / 2;
    static final int Y = 318;

    static final int TITLE_X = 12;
    static final int TITLE_Y = 6;
    static final int TITLE_W = W - 2 * TITLE_X;
    static final int TITLE_H = 32;

    static final int ROW_Y = 40;
    static final int ROW_H = 40;
    // The label reaches 10 px into the gap before the slot: the stock label
    // replaces text wider than itself with "...". Text is right-aligned.
    static final int NUMBER_X = PAD + (int) VolumeLevelWidget.SLOT_X - 10;
    static final int NUMBER_W = (int) VolumeLevelWidget.SLOT_W + 10;

    // 3G+ texts 7377-7385 (K0942 HMISystemD4, en_US), indexed by title
    static final String[] TITLES = {
        "Entertainment volume", "Ring tone volume", "Call volume", "Traffic reports volume",
        "Navigation prompts volume", "Traffic reports volume", "Voice recognition volume",
        "Traffic reports volume", "Currently unavailable"
    };
    static final int UNAVAILABLE = 8;

    private static VolumeBarPopin built;
    private static VolumeTitleLabel titleLabel;
    private static VolumeNumberLabel numberLabel;
    private static VolumeLevelWidget levelWidget;

    private VolumeBarScreen() {
    }

    public static String titleFor(int title, boolean locked) {
        int i = locked ? UNAVAILABLE : title;
        if (i < 0 || i > UNAVAILABLE) {
            i = 0;
        }
        return TITLES[i];
    }

    /** The title for the current state. */
    public static String titleText() {
        return titleFor(VolumeBar.title(), VolumeBar.locked());
    }

    /**
     * Brings title, number and bar up to date and repaints straight away.
     * HMI thread only.
     */
    public static void apply() {
        if (refresh(false)) {
            built.settleOpacity();
            built.triggerRepaint();
        }
    }

    /**
     * Brings the widgets up to date without painting; false if not
     * connected. With all set (when the pop-up appears) the labels are
     * redrawn even if their text didn't change.
     *
     * Never mark the pop-up itself dirty: its render clears the whole
     * container, which drops the widgets' drawings until they reconnect.
     */
    static boolean refresh(boolean all) {
        VolumeBarPopin p = built;
        if (p == null || !p.isConnected()) {
            return false;   // widgets read the state themselves when they connect
        }
        if (all) {
            titleLabel.setCompositesDirty(true);
            numberLabel.setCompositesDirty(true);
        }
        boolean locked = VolumeBar.locked();
        titleLabel.refresh();
        if (numberLabel.isEnabled() == locked) {
            numberLabel.setEnabled(!locked);
        }
        numberLabel.refresh();
        levelWidget.setCompositesDirty(true);
        return true;
    }

    /** Label paints. The MMI colour scheme decides the colour actually used. */
    static SolidPaint[] labelPaints() {
        SolidPaint white = new SolidPaint(0xFFFFFFFF);
        return new SolidPaint[] {
            new SolidPaint(0xFF000000),
            new SolidPaint(0xFFFF0000),
            white,
            white
        };
    }

    /** Puts the pop-up into ToneScreenFactory's unused slot for SCREEN_ID. */
    public static synchronized boolean ensureInstalled() {
        ToneScreenFactory f = ToneScreenFactory.getInstance();
        Screen current = VolumeBarSlot.get(f, VolumeBar.SCREEN_ID);
        if (current != null) {
            return current == built;
        }
        if (built == null) {
            built = (VolumeBarPopin) create(f);
            if (built == null) {
                return false;
            }
        }
        return VolumeBarSlot.install(f, VolumeBar.SCREEN_ID, built);
    }

    /** The pop-up, or null if it hasn't been built. */
    public static VolumeBarPopin popin() {
        return built;
    }

    /** Null on any failure: FwHMI skips a null pop-in, so the worst case is no volume bar. */
    public static Screen create(ToneScreenFactory factory) {
        try {
            return build(factory);
        } catch (Throwable t) {
            VolumeBar.screenFailed(t);
            return null;
        }
    }

    static Screen build(ToneScreenFactory factory) {
        SolidPaint[] paints = labelPaints();

        VolumeTitleLabel title = new VolumeTitleLabel(TITLE_X, TITLE_Y, TITLE_W, TITLE_H);
        title.setAlignment(2, 5);
        title.setFonts(de.audi.tghu.car.hmi.evolution.FontFactory.getFonts(3));   // plain 21
        title.setPaints(paints);

        VolumeLevelWidget bar = new VolumeLevelWidget(PAD, ROW_Y, VolumeLevelWidget.WIDTH, ROW_H);

        VolumeNumberLabel number = new VolumeNumberLabel(NUMBER_X, ROW_Y, NUMBER_W, ROW_H);
        number.setAlignment(3, 5);   // right-aligned
        number.setFonts(de.audi.tghu.car.hmi.evolution.FontFactory.getFonts(2));  // plain 27
        number.setPaints(paints);

        VolumeBarPopin popin = new VolumeBarPopin(VolumeBar.SCREEN_ID, X, Y, W, H);
        popin.setScreenFactory(factory);
        popin.setType(0);
        popin.setMoveBackgroundImageY(0);
        popin.setFonts(de.audi.tghu.car.hmi.evolution.FontFactory.getFonts(2));
        popin.setPaints(paints);
        // No model views: VolumeBar triggers the refreshes (see apply()).
        popin.add(title);
        popin.add(bar);
        popin.add(number);
        titleLabel = title;
        numberLabel = number;
        levelWidget = bar;
        return popin;
    }
}
