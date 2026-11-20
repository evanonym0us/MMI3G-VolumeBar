#!/bin/ksh
# Volume bar: FIRMWARE COPY (read-only)
# Copies lsd.jxe and lsd.sh from the MMI to this card so the volume bar can
# be built from your own unit's software. Nothing on the MMI is changed.
SDPATH=$1
. $SDPATH/common.sh
screen firmware_start
OUT=$SDPATH/var/firmware
mkdir -p $OUT
exec > $SDPATH/var/volbar-firmware.txt 2>&1
echo "volume bar firmware copy  $(date)"

stopped() { echo "STOPPED: $1"; echo "Nothing on the MMI was changed."; finish firmware_stopped 1; }

stock_firmware || stopped "this unit is not running the stock K0133 screen software"
JXE=$ROOT/mnt/ifs-root/lsd/lsd.jxe
rm -f $OUT/lsd.jxe $OUT/lsd.sh
cp $JXE $OUT/lsd.jxe
cp $LSDDIR/lsd.sh $OUT/lsd.sh
[ "$(ck $OUT/lsd.jxe)" = "$(ck $JXE)" ] || stopped "lsd.jxe did not copy correctly (SD card full?)"
[ "$(ck $OUT/lsd.sh)" = "$LSDSH_CKSUM" ] || stopped "lsd.sh did not copy correctly"
echo "lsd.jxe $(ck $OUT/lsd.jxe)"
echo "lsd.sh  $(ck $OUT/lsd.sh)"
echo "COPIED. Put var/firmware/lsd.jxe and lsd.sh in the firmware folder of the project."
finish firmware_ok 0
