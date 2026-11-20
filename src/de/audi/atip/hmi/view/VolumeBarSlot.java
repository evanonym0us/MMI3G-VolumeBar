package de.audi.atip.hmi.view;

/**
 * Puts a ready-made screen into a screen factory's cache slot, so
 * getScreen(id) returns it without the factory's createScreen() ever
 * being involved. Lives in this package only to reach the protected
 * "screens" array. Never replaces a screen that is already there.
 */
public final class VolumeBarSlot {

    private VolumeBarSlot() {
    }

    /** @return the screen now in the slot (ours or an existing one), or null. */
    public static Screen get(AbstractScreenFactory f, int id) {
        int i = index(f, id);
        return i < 0 ? null : f.screens[i];
    }

    /** @return true if the slot holds the given screen afterwards. */
    public static boolean install(AbstractScreenFactory f, int id, Screen s) {
        int i = index(f, id);
        if (i < 0 || s == null) {
            return false;
        }
        synchronized (f) {
            if (f.screens[i] == null) {
                f.screens[i] = s;
            }
            return f.screens[i] == s;
        }
    }

    private static int index(AbstractScreenFactory f, int id) {
        if (f == null || f.screens == null || id / 10000 != f.moduleID) {
            return -1;
        }
        int i = id % 10000;
        return i < f.screens.length ? i : -1;
    }
}
