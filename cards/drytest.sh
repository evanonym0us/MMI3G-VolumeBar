#!/bin/bash
# Runs the cards against a fake MMI file layout. MOUNT is a no-op recorder.
# Needs ksh, and firmware/lsd.sh from your own unit (see README).
set -u
cd "$(dirname "$0")"
LSDSH=../firmware/lsd.sh
[ -f $LSDSH ] || { echo "missing $LSDSH"; exit 1; }
pass=0; fail=0
t(){ if eval "$2"; then echo "  PASS  $1"; pass=$((pass+1)); else echo "  FAIL  $1"; fail=$((fail+1)); fi; }
FR=/tmp/fakemmi
reset_root(){
  rm -rf $FR; mkdir -p $FR/mnt/efs-system/lsd $FR/mnt/ifs-root/lsd $FR/tmp
  cp $LSDSH $FR/mnt/efs-system/lsd/lsd.sh
  truncate -s 13881111 $FR/mnt/ifs-root/lsd/lsd.jxe
  ln -s $FR/mnt/efs-system/lsd $FR/lsd
  rm -f /tmp/mountlog
}
stub_show(){ # replace the SH4 binary with a recorder (also logs mount state when shown)
  printf '#!/bin/sh\necho "$(basename $1 .png)" >> /tmp/shown\necho "SCREEN $(basename $1 .png)" >> /tmp/mountlog\n' > /tmp/sd/bin/showScreen
  chmod +x /tmp/sd/bin/showScreen; rm -f /tmp/shown
}
card(){ # card name -> fresh sd copy, run it
  rm -rf /tmp/sd; cp -r ../build/cards/$1 /tmp/sd; stub_show
  ROOT=$FR MOUNT="eval echo mount >>/tmp/mountlog" MUVER=${MUV:-MMI3GH} ksh /tmp/sd/run.sh /tmp/sd
}
shown(){ cat /tmp/shown 2>/dev/null | tr '\n' ' '; }
J=$FR/mnt/efs-system/lsd/TestServer.jar
NEWCK="$(cksum ../build/jar/TestServer.jar | awk '{print $1" "$2}')"

echo "== install on stock MMI"
reset_root; card volbar-install
t "reports INSTALLED" "grep -q '^INSTALLED' /tmp/sd/var/volbar-install.txt"
t "jar in place and identical" "cmp -s $J ../build/jar/TestServer.jar"
t "no leftover .new" "[ ! -e $J.new ]"
t "efs writable, read-only, SD flushed, then result screen" "[ \"\$(grep -v 'SCREEN install_start' /tmp/mountlog | tr '\n' ' ')\" = 'mount -uw $FR/mnt/efs-system mount -ur $FR/mnt/efs-system mount -ur /tmp/sd SCREEN install_ok ' ]"
t "screens: start then installed" "[ \"\$(shown)\" = 'install_start install_ok ' ]"
t "log complete on card" "tail -1 /tmp/sd/var/volbar-install.txt | grep -q '^INSTALLED'"
t "visible under /lsd" "grep -q 'visible to the screen' /tmp/sd/var/volbar-install.txt"

echo "== install again"
rm -f /tmp/mountlog; card volbar-install
t "says already installed, efs not touched" "grep -q 'ALREADY INSTALLED' /tmp/sd/var/volbar-install.txt && ! grep -q efs-system /tmp/mountlog"
t "screen: already installed" "[ \"\$(shown)\" = 'install_start install_already ' ]"

echo "== report"
echo "123 model created id=100027" > $FR/tmp/volumebar.log
card volbar-report
t "report shows installed" "grep -q \"installed: $NEWCK\" /tmp/sd/var/volbar-report.txt"
t "log copied to card" "cmp -s $FR/tmp/volumebar.log /tmp/sd/var/volumebar.log"
t "screens: start then report saved" "[ \"\$(shown)\" = 'report_start report_ok ' ]"

echo "== remove"
rm -f /tmp/mountlog; card volbar-remove
t "reports REMOVED" "grep -q '^REMOVED' /tmp/sd/var/volbar-remove.txt"
t "jar gone" "[ ! -e $J ]"
t "screens: start then removed" "[ \"\$(shown)\" = 'remove_start remove_ok ' ]"
t "lsd folder otherwise untouched" "[ \"\$(ls $FR/mnt/efs-system/lsd)\" = 'lsd.sh' ]"
card volbar-remove
t "remove again: not installed" "grep -q 'NOT INSTALLED' /tmp/sd/var/volbar-remove.txt && [ \"\$(shown)\" = 'remove_start remove_none ' ]"

echo "== safety stops (nothing must change)"
reset_root; MUV=MMI3GP card volbar-install
t "wrong unit type: stops, stopped screen" "grep -q STOPPED /tmp/sd/var/volbar-install.txt && [ ! -e $J ] && ! grep -q efs-system /tmp/mountlog && [ \"\$(shown)\" = 'install_start install_stopped ' ]"
reset_root; echo "# changed" >> $FR/mnt/efs-system/lsd/lsd.sh; card volbar-install
t "modified lsd.sh: stops" "grep -q STOPPED /tmp/sd/var/volbar-install.txt && [ ! -e $J ]"
reset_root; truncate -s 13881112 $FR/mnt/ifs-root/lsd/lsd.jxe; card volbar-install
t "different lsd.jxe: stops" "grep -q STOPPED /tmp/sd/var/volbar-install.txt && [ ! -e $J ]"
reset_root; echo foreign > $J; card volbar-install
t "foreign TestServer.jar: install stops" "grep -q 'different TestServer.jar' /tmp/sd/var/volbar-install.txt && [ \"\$(cat $J)\" = foreign ]"
card volbar-remove
t "foreign TestServer.jar: remove leaves it" "grep -q 'Left it alone' /tmp/sd/var/volbar-remove.txt && [ -e $J ] && [ \"\$(shown)\" = 'remove_start remove_stopped ' ]"
reset_root; rm -rf /tmp/sd; cp -r ../build/cards/volbar-install /tmp/sd; stub_show; printf 'x' >> /tmp/sd/payload/TestServer.jar
ROOT=$FR MOUNT="eval echo mount >>/tmp/mountlog" MUVER=MMI3GH ksh /tmp/sd/run.sh /tmp/sd
t "damaged card: stops" "grep -q 'damaged' /tmp/sd/var/volbar-install.txt && [ ! -e $J ]"

echo "== copy fails halfway"
reset_root; rm -rf /tmp/sd; cp -r ../build/cards/volbar-install /tmp/sd; stub_show
mkdir -p /tmp/fakebin && printf '#!/bin/sh\nhead -c 1000 "$1" > "$2"\n' > /tmp/fakebin/cp && chmod +x /tmp/fakebin/cp
PATH=/tmp/fakebin:$PATH ROOT=$FR MOUNT="eval echo mount >>/tmp/mountlog" MUVER=MMI3GH ksh /tmp/sd/run.sh /tmp/sd
t "bad copy: cleaned up, efs back to read-only" "grep -q 'did not verify' /tmp/sd/var/volbar-install.txt && [ ! -e $J ] && [ ! -e $J.new ] && grep -v SCREEN /tmp/mountlog | tail -2 | head -1 | grep -q 'efs-system' && [ \"\$(shown)\" = 'install_start install_stopped ' ]"

echo "== upgrade from an older version"
reset_root; printf 'old volume bar jar' > $J; OLDCK="$(cksum $J | awk '{print $1" "$2}')"
for c in volbar-install volbar-remove; do sed -i "s/^OLD_CKSUMS=.*/OLD_CKSUMS=\"$OLDCK\"/" ../build/cards/$c/common.sh; done
rm -f /tmp/mountlog; card volbar-install
t "older version replaced" "grep -q 'UPDATED from the older version' /tmp/sd/var/volbar-install.txt && cmp -s $J ../build/jar/TestServer.jar && [ \"\$(shown)\" = 'install_start install_ok ' ]"
printf 'old volume bar jar' > $J; card volbar-remove
t "remove card also removes an older version" "grep -q '^REMOVED' /tmp/sd/var/volbar-remove.txt && [ ! -e $J ]"
./pack.sh >/dev/null

echo "== a jar you built yourself (unknown checksum, has the marker)"
reset_root; printf 'PK junk de/audi/tghu/tone/hmi/evolution/VolumeBarPopin.class more junk' > $J
rm -f /tmp/mountlog; card volbar-install
t "own build replaced" "grep -q 'UPDATED from the older version' /tmp/sd/var/volbar-install.txt && cmp -s $J ../build/jar/TestServer.jar"
printf 'PK junk de/audi/tghu/tone/hmi/evolution/VolumeBarPopin.class more junk' > $J; card volbar-remove
t "own build removed" "grep -q '^REMOVED' /tmp/sd/var/volbar-remove.txt && [ ! -e $J ]"

echo "== firmware copy"
reset_root; head -c 13881111 /dev/urandom > $FR/mnt/ifs-root/lsd/lsd.jxe; rm -f /tmp/mountlog; card volbar-firmware
t "reports COPIED" "grep -q '^COPIED' /tmp/sd/var/volbar-firmware.txt"
t "lsd.jxe and lsd.sh copied exactly" "cmp -s $FR/mnt/ifs-root/lsd/lsd.jxe /tmp/sd/var/firmware/lsd.jxe && cmp -s $LSDSH /tmp/sd/var/firmware/lsd.sh"
t "MMI never made writable" "! grep -q efs-system /tmp/mountlog"
t "screens: start then copied" "[ \"\$(shown)\" = 'firmware_start firmware_ok ' ]"
reset_root; MUV=MMI3GP card volbar-firmware
t "wrong unit type: stops" "grep -q STOPPED /tmp/sd/var/volbar-firmware.txt && [ ! -e /tmp/sd/var/firmware/lsd.jxe ] && [ \"\$(shown)\" = 'firmware_start firmware_stopped ' ]"

echo "== final step fails"
reset_root; rm -rf /tmp/sd; cp -r ../build/cards/volbar-install /tmp/sd; stub_show
mkdir -p /tmp/fakebin2 && printf '#!/bin/sh\nhead -c 500 "$1" > "$2"; rm -f "$1"\n' > /tmp/fakebin2/mv && chmod +x /tmp/fakebin2/mv
PATH=/tmp/fakebin2:$PATH ROOT=$FR MOUNT="eval echo mount >>/tmp/mountlog" MUVER=MMI3GH ksh /tmp/sd/run.sh /tmp/sd
t "bad final file: problem screen, efs read-only" "grep -q PROBLEM /tmp/sd/var/volbar-install.txt && [ \"\$(shown)\" = 'install_start install_problem ' ] && grep -v SCREEN /tmp/mountlog | tail -2 | head -1 | grep -q efs-system"
echo "printf" > /dev/null
cp ../build/jar/TestServer.jar $J
rm -rf /tmp/sd; cp -r ../build/cards/volbar-remove /tmp/sd; stub_show
mkdir -p /tmp/fakebin3 && printf '#!/bin/sh\nexit 0\n' > /tmp/fakebin3/rm && chmod +x /tmp/fakebin3/rm
PATH=/tmp/fakebin3:$PATH ROOT=$FR MOUNT="eval echo mount >>/tmp/mountlog" MUVER=MMI3GH ksh /tmp/sd/run.sh /tmp/sd
t "delete fails: problem screen" "grep -q PROBLEM /tmp/sd/var/volbar-remove.txt && [ \"\$(shown)\" = 'remove_start remove_problem ' ]"

echo; echo "RESULT: $pass passed, $fail failed"
