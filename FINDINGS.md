# Findings: MMI 3G High internals

Notes from building this project, for anyone writing their own mods for the MMI 3G High. Everything here was checked on K0133 (HNav) in a 2010 S5. Class and method names come from `lsd.jxe` converted with jxe2jar. Other software versions will be similar but not identical.

## Running your own Java code

- The screen software (`lsd.jxe`) is started by `lsd.sh`. Before starting the VM, the script checks for `TestServer.jar`, `DSITracer.jar` and `xEventRecorder.jar` in its folder and adds any it finds to `-Xbootclasspath`, ahead of `lsd.jxe`. A class in one of those jars replaces the stock class with the same name.
- The folder is `/mnt/efs-system/lsd`, also visible as `/lsd`. `efs-system` is flash and mounted read-only. Use `mount -uw /mnt/efs-system` to write and `mount -ur /mnt/efs-system` afterwards. The file survives restarts, and deleting it puts the unit back to stock.
- The VM is IBM J9 with the Foundation 1.0 class library (roughly Java 1.3 level). Compile with JDK 8 using `-source 1.3 -target 1.2` and `-bootclasspath` pointed at the converted `lsd.jxe`, so you can only use what the unit has. Class file version 46 loads fine.
- The bootclasspath packages aren't sealed, so a helper class can be put into a stock package to reach package-private or protected members. This project does that twice: `de.audi.tghu.fwhmi.VolumeBarHmi` (for `FwHMI.getCurrentScreen()`) and `de.audi.atip.hmi.view.VolumeBarSlot` (for a screen factory's `screens` cache).
- To change stock behavior, replace the stock class with a patched copy. Patching the bytecode with ASM works better than recompiling decompiled source: the conversion loses some details, and a small inserted call is easy to verify. Wrap every inserted call in `try { ... } catch (Throwable)` so a bug in your code can't take down the stock path.
- SD card scripts are started by a separate native process (`proc_scriptlauncher`), not by the Java software, so a remove card should still work if your jar breaks the screen. Keep one handy.

## Volume plumbing

- `de.audi.tghu.tone.app.SoundListener.updateVolume(int conn, short vol, int valid)` gets every volume report from the audio system, for every connection, not just the one you hear.
- `MenuVolumeHandler.changeAudibleVolume(int)` runs on each knob step. `MenuVolumeHandler.keyTyped(int)` runs on a knob press. The handler also serves other models, so check that it's the volume model (`100021`, `ToneModelBank.VOLUME_RANGE`) before acting.
- `ConnectionStore.hasFocus(conn)` tells you which connection is audible, so you know which volume the knob is changing. Connection groups, in the 3G+ priority order:

  | Volume | Connection IDs |
  | --- | --- |
  | Navigation prompts | 83, 116, 117 |
  | Traffic reports (TA) | 84, 31 to 34 |
  | Voice recognition | 86, 112, 113 |
  | Ring tone | 87, 98, 103 |
  | Call | 90, 97, 99 to 102, 104 to 107, 110, 111 |
  | Traffic messages (TMC) | 114, 115, 118, 119 |
  | Entertainment | everything else |

- Connection 5 (`AudioConnection.ENT_VOLUME_LOCK`) means the entertainment volume is locked. On this car it showed up when switching to an external audio input.
- All entertainment sources share one volume. Its top is 34 on this car.
- The mute connection stays started even after you unmute, so don't treat "mute connection active" as muted. Use the level instead.
- Volume reports for connections that aren't playing can arrive at any time and carry stale levels and maximums. Only trust entertainment reports when entertainment is what's audible.

## Screens and pop-ups

- The display is 800 x 480. The clock line sits at y 422.
- Pop-ups are `ScreenWidgetPopin` subclasses. The climate pop-up uses it and is a good example to read. Show one with the HMI service's `showPopin(terminal, screenId)`, terminal 0 for the front screen. The screen ID has to be free in the screen factory. `ToneScreenFactory` has an unused slot at `100011`, and `ToneModelBank` has room for one more model at index 27 (ID `100027`).
- The MMI calls `hidePopin` on your pop-up by itself during some screen transitions. That doesn't disconnect it, so you can show it again without reconnecting.
- `FwHMI.getCurrentScreen()` gives the current main screen ID, which is enough to notice a screen change.
- `IDisplayManager.getCurrentContextID(0) == HMIService.CONTEXT_REARVIEW_CAMERA` (11) means the reverse camera is on screen.
- Fonts: `FontFactory.getFonts(3)` is the plain 21 px font, `getFonts(2)` the plain 27 px one.
- K0942 (the 3G+ software) has the original volume pop-up in `SystemScreenFactory` (`partialPopupVolume`). It's a useful reference for layout.

## Drawing and repaint pitfalls

These cost the most time. Each caused a visible bug on the car.

- A pop-in's `processModelUpdateEvent` defers repaints while any animation is running anywhere on screen. If your widget updates through a model, it can freeze until the animation ends. On the Bluetooth screen that meant it froze for good. Calling `triggerRepaint()` on the pop-in directly avoids it.
- Never mark the pop-in itself dirty (`setCompositesDirty`, `setCompositeDirty`, `setInvalid`). `ScreenWidgetPopin.render` then clears every drawable in the container and your widgets vanish. Mark the child widgets dirty instead.
- `RangeModel.setLimits` and `setValue` fire update events even when nothing changed. Compare first.
- Labels register with the `AnimationController` as fading widgets. App-change animations then call `setOpacity` on them and can leave them invisible. Overriding `setOpacity` as a no-op on your labels fixes it. Fade the container instead.
- `AbstractWidget.initializeDefaultComposite` applies the widget's opacity on every render, and `initConnect` resets it to 1. Reapply your fade value after a render.
- Fading by changing the container's opacity is cheap because the compositor applies it without redrawing anything. 40 ms frames (25 fps) look smooth and add no visible load.
- Draw static things, like the panel background, once into a bitmap and reuse it.

## Performance

- The CPU is an SH4. Use one shared `java.util.Timer` instead of starting threads, don't allocate on each frame, and coalesce repaints so several updates in one frame cost one draw.
- `SoundListener.updateVolume` is on the audio path, so return quickly.
- `/tmp` is in RAM and cleared on every restart. It's a fine place for a small log. Cap its size.

## SD card scripting

- The MMI runs `copie_scr.sh` from the root of an inserted SD card. Use DrGER's version: it passes the card's real mount path as `$1`, which the stock one gets wrong when other media is connected.
- The shell is QNX 6.3's `ksh`, which is pdksh-based. It predefines aliases (`autoload functions hash history integer local login nohup r stop suspend type true false`), and a function with one of those names fails with a syntax error at parse time. `cards/lint.sh` checks for this.
- `head`, `tr` and `sync` aren't there. `ls`, `cp`, `mv`, `rm`, `cksum`, `grep`, `mkdir`, `mount`, `cat` and `echo` are.
- `mount -ur` on the SD card's own mount point flushes it, which is the only safe way to make sure a log is written before someone pulls the card.
- `MUVER` is set in the script's environment. It is `MMI3GH` on a 3G High.
- `showScreen` (from DrGER's MMI3G-Info card) shows an 800 x 480 PNG full screen and waits for a button press. After it runs, parts of the map can show through other screens until the next MMI restart. That's a display layer issue, not damage.
- `date` shows 1970 until the unit gets the time from GPS.
- The `lsd.sh` on the car differs by one byte from the copy in the K0133 update package. If you checksum it, use the one from the car.
- Restart the MMI by holding MENU, the knob and the top-right button.
