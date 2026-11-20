#!/bin/bash
# Builds the volume bar against your own lsd.jxe, runs every test and makes
# the SD cards in dist/. See README.md.
#
#   ./build.sh                  full build (needs firmware/lsd.jxe + lsd.sh)
#   ./build.sh firmware-card    only the card that copies those two files off the car
set -e
cd "$(dirname "$0")"
./scripts/fetch-deps.sh

if [ "$1" = firmware-card ]; then
  ./cards/pack.sh firmware
  exit 0
fi

JXE=firmware/lsd.jxe
[ -f $JXE ] && [ -f firmware/lsd.sh ] || { echo "Put lsd.jxe and lsd.sh from your car in firmware/ first (README, step 1)."; exit 1; }
[ "$(stat -c %s $JXE)" = 13881111 ] || { echo "$JXE is not the K0133 one. Only K0133 is supported."; exit 1; }

J8=${JAVA8_HOME:-$JAVA_HOME}
[ -n "$J8" ] || J8=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
"$J8/bin/javac" -version 2>&1 | grep -q '1\.8\.' || { echo "Needs JDK 8. Set JAVA8_HOME to a JDK 8 folder."; exit 1; }
CP=deps/asm-9.7.jar:deps/asm-tree-9.7.jar:deps/asm-analysis-9.7.jar
K=build/k0133.jar
quiet() { grep -v '^Picked up' || true; }

mkdir -p build
if [ ! -f $K ] || [ $JXE -nt $K ]; then
  echo "== Converting lsd.jxe (takes a minute)"
  python3 deps/jxe2jar/src/jxe2jar.py $JXE $K.part
  mv $K.part $K
fi

echo "== Compile, patch, verify"
rm -rf build/classes build/patched build/test build/test-degraded build/orig build/patcher build/jar
mkdir -p build/classes build/patched build/test build/test-degraded build/orig build/patcher build/jar/stage
(cd build/orig && unzip -q -o ../k0133.jar de/audi/tghu/tone/app/SoundListener.class de/audi/tghu/tone/app/ToneModelBank.class de/audi/tghu/tone/app/MenuVolumeHandler.class)
"$J8/bin/javac" -nowarn -Xlint:-options -source 1.3 -target 1.2 -bootclasspath $K -d build/classes $(find src -name '*.java') 2>&1 | quiet
"$J8/bin/javac" -nowarn -d build/patcher -cp $CP patcher/Patch.java patcher/Verify.java 2>&1 | quiet
"$J8/bin/java" -cp build/patcher:$CP Patch $K build/patched 2>&1 | quiet
"$J8/bin/java" -cp build/patcher:$CP Verify $K build/classes build/patched build/orig 2>&1 | quiet

echo "== Tests"
"$J8/bin/javac" -nowarn -XDignore.symbol.file -d build/test -cp build/patched:build/classes:$K test/de/audi/tghu/tone/app/VolumeBarHarness.java 2>&1 | quiet
"$J8/bin/java" -Xverify:all -cp build/test:build/patched:build/classes:$K de.audi.tghu.tone.app.VolumeBarHarness build/volumebar-test.log 2>&1 | quiet
echo "== Degraded run (volume bar classes missing)"
"$J8/bin/javac" -nowarn -XDignore.symbol.file -d build/test-degraded -cp build/patched:$K test/de/audi/tghu/tone/app/DegradedHarness.java 2>&1 | quiet
"$J8/bin/java" -Xverify:all -cp build/test-degraded:build/patched:$K de.audi.tghu.tone.app.DegradedHarness 2>&1 | quiet

echo "== TestServer.jar"
cp -r build/classes/. build/patched/. build/jar/stage/
# Fixed file dates so the same source always gives the same jar.
find build/jar/stage -exec touch -h -t 201001010000 {} +
(cd build/jar/stage && find . -name '*.class' | sed 's#^\./##' | LC_ALL=C sort > ../contents.txt && TZ=UTC zip -q -X -0 -D ../TestServer.jar $(cat ../contents.txt))
cksum build/jar/TestServer.jar

echo "== SD cards"
./cards/pack.sh
if command -v ksh >/dev/null; then
  ./cards/drytest.sh
else
  echo "ksh not installed, skipped the card tests"
fi
