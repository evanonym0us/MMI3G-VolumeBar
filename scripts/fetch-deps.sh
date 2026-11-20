#!/bin/bash
# Downloads the build tools and the two SD card helpers into deps/.
# Everything is pinned to an exact version and checked against a SHA-256.
set -e
cd "$(dirname "$0")/.."
mkdir -p deps
cd deps

get() { # file url sha256
  if [ -f "$1" ] && echo "$3  $1" | sha256sum -c --status; then
    echo "ok       $1"
    return
  fi
  echo "fetching $1"
  curl -fsSL -o "$1.part" "$2"
  echo "$3  $1.part" | sha256sum -c --status || { echo "checksum mismatch for $1"; rm -f "$1.part"; exit 1; }
  mv "$1.part" "$1"
}

MAVEN=https://repo1.maven.org/maven2/org/ow2/asm
get asm-9.7.jar          $MAVEN/asm/9.7/asm-9.7.jar                   adf46d5e34940bdf148ecdd26a9ee8eea94496a72034ff7141066b3eea5c4e9d
get asm-tree-9.7.jar     $MAVEN/asm-tree/9.7/asm-tree-9.7.jar         62f4b3bc436045c1acb5c3ba2d8ec556ec3369093d7f5d06c747eb04b56d52b1
get asm-analysis-9.7.jar $MAVEN/asm-analysis/9.7/asm-analysis-9.7.jar 7bc6bcbc21379948a0c8c467fb0f864206e5b818f6bc0b546872f5c9f941556f

# DrGER's SD card launcher and the showScreen tool from his MMI3G-Info card
get copie_scr.sh https://raw.githubusercontent.com/DrGER2/copie_src.sh/19f9b76e331ad945b5d832e7f82d336a17befb3a/copie_scr.sh \
    fb9325571a86a7881e0d254530370c69af31366eb967ba0ce32962cc6065b8d3
get showScreen https://raw.githubusercontent.com/DrGER2/MMI3G-Info/2d6f0e419cfd22c7daf3b38fac0418c99a0e0de0/script/bin/showScreen \
    033174eb41f30a6197aac7fafcfbe22adcdeb0c637e9a43937ee90d20fd9bdd5

# jxe2jar turns lsd.jxe into a normal jar to compile and patch against.
# Only its src folder is needed, so this skips the large history and extras.
JXE_COMMIT=367e99bee3c90e72de0de1edc1be0529258fd7f2
if [ -f jxe2jar/src/jxe2jar.py ] && [ "$(git -C jxe2jar rev-parse HEAD 2>/dev/null)" = "$JXE_COMMIT" ]; then
  echo "ok       jxe2jar"
else
  echo "fetching jxe2jar"
  rm -rf jxe2jar
  git init -q jxe2jar
  git -C jxe2jar remote add origin https://github.com/luka-dev/jxe2jar.git
  git -C jxe2jar sparse-checkout set src
  git -C jxe2jar fetch -q --depth 1 --filter=blob:none origin $JXE_COMMIT
  git -C jxe2jar checkout -q FETCH_HEAD
fi
echo "deps ready"
