# Generates the 800x480 status screens shown on the MMI by showScreen.
from PIL import Image, ImageDraw, ImageFont
import os
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screens")
F = "/usr/share/fonts/truetype/dejavu/"
title_f = ImageFont.truetype(F + "DejaVuSans-Bold.ttf", 46)
body_f = ImageFont.truetype(F + "DejaVuSans.ttf", 28)
small_f = ImageFont.truetype(F + "DejaVuSans.ttf", 22)
GREEN, AMBER, RED, BLUE = (60, 200, 90), (240, 180, 40), (235, 70, 60), (90, 160, 240)

SAFE = "It is safe to remove the SD card."
PRESS = "Press any button to close this screen."

screens = {
    "install_ok":      (GREEN, "Volume bar installed", [SAFE, "", "Then restart the MMI:", "hold MENU + knob + top-right button."]),
    "install_already": (GREEN, "Already installed", ["Nothing needed to change.", "", SAFE]),
    "install_stopped": (AMBER, "Install stopped", ["Nothing on the MMI was changed.", "", SAFE, "Details: var/volbar-install.txt"]),
    "install_problem": (RED,   "Install problem", [SAFE, "", "Run the REMOVE card next.", "Details: var/volbar-install.txt"]),
    "remove_ok":       (GREEN, "Volume bar removed", [SAFE, "", "Then restart the MMI:", "hold MENU + knob + top-right button."]),
    "remove_none":     (GREEN, "Not installed", ["Nothing needed to change.", "", SAFE]),
    "remove_stopped":  (AMBER, "Remove stopped", ["The file on the MMI is not the volume bar.", "It was left alone.", "", SAFE]),
    "remove_problem":  (RED,   "Remove problem", [SAFE, "", "Details: var/volbar-remove.txt"]),
    "install_start":   (BLUE,  "Volume bar installer", ["Press any button to start.", "", "Do NOT remove the SD card until", "the finished screen appears."]),
    "remove_start":    (BLUE,  "Volume bar remover", ["Press any button to start.", "", "Do NOT remove the SD card until", "the finished screen appears."]),
    "report_start":    (BLUE,  "Volume bar report", ["Press any button to start.", "", "Do NOT remove the SD card until", "the finished screen appears."]),
    "report_ok":       (BLUE,  "Report saved", [SAFE, "", "The report is in the var folder."]),
    "firmware_start":  (BLUE,  "Firmware copy", ["Press any button to start.", "", "Do NOT remove the SD card until", "the finished screen appears."]),
    "firmware_ok":     (GREEN, "Firmware copied", [SAFE, "", "The files are in var/firmware.", "Nothing on the MMI was changed."]),
    "firmware_stopped":(AMBER, "Copy stopped", ["Nothing on the MMI was changed.", "", SAFE, "Details: var/volbar-firmware.txt"]),
}

for name, (color, title, lines) in screens.items():
    im = Image.new("RGBA", (800, 480), (0, 0, 0, 255))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 800, 10], fill=color)
    d.text((400, 95), title, font=title_f, fill=color, anchor="mm")
    y = 175
    for ln in lines:
        if ln:
            d.text((400, y), ln, font=body_f, fill=(235, 235, 235), anchor="mm")
        y += 42
    if not name.endswith("_start"):
        d.text((400, 440), PRESS, font=small_f, fill=(150, 150, 150), anchor="mm")
    im.save(os.path.join(OUT, name + ".png"), optimize=True)
print("ok", len(screens))
