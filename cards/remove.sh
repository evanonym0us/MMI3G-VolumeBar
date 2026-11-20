#!/bin/ksh
# Volume bar: REMOVE
# Deletes /mnt/efs-system/lsd/TestServer.jar, putting the MMI back to stock.
SDPATH=$1
. $SDPATH/common.sh
screen remove_start
mkdir -p $SDPATH/var
exec > $SDPATH/var/volbar-remove.txt 2>&1
echo "volume bar remove  $(date)"

if [ ! -e $TARGET ]; then
  echo "NOT INSTALLED. Nothing to do."
  finish remove_none 0
fi
if ! ours $TARGET; then
  echo "STOPPED: TestServer.jar on the MMI is not the volume bar one ($(ck $TARGET))."
  echo "Left it alone."
  finish remove_stopped 1
fi
$MOUNT -uw $EFS || { echo "PROBLEM: could not make efs-system writable"; finish remove_problem 1; }
rm -f $TARGET $TARGET.new
$MOUNT -ur $EFS
if [ -e $TARGET ]; then
  echo "PROBLEM: file is still there."
  finish remove_problem 1
fi
echo "REMOVED. Restart the MMI (hold MENU + knob + top right button)."
finish remove_ok 0
