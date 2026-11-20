package de.audi.tghu.fwhmi;

import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.view.IDisplayManager;

/**
 * What the front display is showing, for the volume pop-up. Lives in this
 * package only to reach FwHMI.getCurrentScreen(), which is package-private.
 * Both reads are plain field lookups.
 */
public final class VolumeBarHmi {

    private VolumeBarHmi() {
    }

    /** Id of the main screen, or -1 if it can't be read. */
    public static int screen() {
        try {
            FwHMI hmi = FwHMI.getHMIServiceImpl();
            return hmi == null ? -1 : hmi.getCurrentScreen();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** True while the rear view camera is on screen. */
    public static boolean rearCamera() {
        try {
            FwHMI hmi = FwHMI.getHMIServiceImpl();
            IDisplayManager dm = hmi == null ? null : hmi.getDisplayManager();
            return dm != null && dm.getCurrentContextID(0) == HMIService.CONTEXT_REARVIEW_CAMERA;
        } catch (Throwable t) {
            return false;
        }
    }
}
