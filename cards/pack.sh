#!/bin/bash
# Assembles the SD cards from build/jar/TestServer.jar and zips them into dist/.
# "pack.sh firmware" makes only the firmware copy card, which needs no jar.
set -e
cd "$(dirname "$0")"
./lint.sh
DEPS=../deps
JAR=../build/jar/TestServer.jar
OUTDIR=../build/cards
DIST=../dist
CARDS="volbar-install volbar-remove volbar-report volbar-firmware"
NEED="$DEPS/showScreen $DEPS/copie_scr.sh $JAR"
if [ "$1" = firmware ]; then CARDS=volbar-firmware; NEED="$DEPS/showScreen $DEPS/copie_scr.sh"; fi
for f in $NEED; do
  [ -f $f ] || { echo "missing $f"; exit 1; }
done
CK=none
[ -f $JAR ] && CK=$(cksum $JAR | awk '{print $1" "$2}')
mkdir -p $OUTDIR $DIST
mk() { # card-name script screen-prefix
  d=$OUTDIR/$1; rm -rf $d; mkdir -p $d/bin $d/lib
  cp $DEPS/copie_scr.sh $d/
  sed "s/@JAR_CKSUM@/$CK/" common.sh > $d/common.sh
  cp $2 $d/run.sh
  cp $DEPS/showScreen $d/bin/showScreen; chmod +x $d/bin/showScreen
  cp screens/$3_*.png $d/lib/
}
mk volbar-firmware firmware.sh firmware
if [ "$CARDS" != volbar-firmware ]; then
  mk volbar-install install.sh install
  mkdir -p $OUTDIR/volbar-install/payload && cp $JAR $OUTDIR/volbar-install/payload/
  mk volbar-remove remove.sh remove
  mk volbar-report report.sh report
fi
for c in $CARDS; do
  rm -f $DIST/$c.zip; (cd $OUTDIR/$c && zip -q -X -r ../../../dist/$c.zip .)
done
ls -l $DIST
