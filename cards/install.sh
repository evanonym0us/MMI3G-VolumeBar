#!/bin/ksh
# Volume bar: INSTALL
# Copies TestServer.jar into /mnt/efs-system/lsd. Nothing else is changed.
# The screen software picks it up at the next MMI restart.
SDPATH=$1
. $SDPATH/common.sh
screen install_start
mkdir -p $SDPATH/var
exec > $SDPATH/var/volbar-install.txt 2>&1
echo "volume bar install  $(date)"

bail() { echo "STOPPED: $1"; echo "Nothing on the MMI was changed."; finish install_stopped 1; }

stock_firmware || bail "firmware check failed"
[ "$(ck $SDPATH/payload/TestServer.jar)" = "$JAR_CKSUM" ] || bail "TestServer.jar on this card is damaged, re-copy the card"

UPGRADE=no
if [ -e $TARGET ]; then
  if [ "$(ck $TARGET)" = "$JAR_CKSUM" ]; then
    echo "ALREADY INSTALLED (same version). Nothing to do."
    finish install_already 0
  fi
  ours $TARGET || bail "a different TestServer.jar is already on the MMI ($(ck $TARGET))"
  echo "older volume bar version found ($(ck $TARGET)), replacing it"
  UPGRADE=yes
fi

$MOUNT -uw $EFS || bail "could not make efs-system writable"
cp $SDPATH/payload/TestServer.jar $TARGET.new
if [ "$(ck $TARGET.new)" != "$JAR_CKSUM" ]; then
  rm -f $TARGET.new
  $MOUNT -ur $EFS
  bail "copy did not verify, removed it again"
fi
mv $TARGET.new $TARGET
OK=yes
[ "$(ck $TARGET)" = "$JAR_CKSUM" ] || OK=no
$MOUNT -ur $EFS

if [ $OK = no ]; then
  echo "PROBLEM: file did not verify after the final step. Run the REMOVE card."
  finish install_problem 1
fi
if [ -e $LIVE ]; then
  echo "visible to the screen software as $LIVE"
else
  echo "NOTE: not visible under /lsd yet. It may appear after restart."
fi
[ $UPGRADE = yes ] && echo "UPDATED from the older version."
echo "INSTALLED. Restart the MMI (hold MENU + knob + top right button)."
finish install_ok 0
