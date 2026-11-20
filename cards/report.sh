#!/bin/ksh
# Volume bar: REPORT (read-only)
# Copies the volume bar log and install state to this card.
SDPATH=$1
. $SDPATH/common.sh
screen report_start
mkdir -p $SDPATH/var
exec > $SDPATH/var/volbar-report.txt 2>&1
echo "volume bar report  $(date)"
if [ -e $TARGET ]; then echo "installed: $(ck $TARGET)"; else echo "installed: no"; fi
if [ -e $LIVE ]; then echo "visible in /lsd: yes"; else echo "visible in /lsd: no"; fi
echo
echo "==== j9"
pidin arg 2>/dev/null | grep -i "j9 "
echo
echo "==== files the screen software has open (proves the jar is loaded)"
pidin fds 2>/dev/null | grep -i -e TestServer -e DSITracer -e lsd.jxe
echo
echo "==== volumebar.log"
if [ -e $ROOT/tmp/volumebar.log ]; then
  cat $ROOT/tmp/volumebar.log
  cp $ROOT/tmp/volumebar.log $SDPATH/var/volumebar.log
else
  echo "(no log: the volume bar has not run since the last restart)"
fi
finish report_ok 0
