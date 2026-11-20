# Shared settings for the volume bar SD cards.
# Only uses commands present on the MMI 3G High: ls, cp, mv, rm, cksum, grep,
# mkdir, mount, cat, echo (no head, tr, sync), plus showScreen from this card.
# ROOT and MOUNT exist only so the scripts can be tested on a computer.
ROOT=${ROOT:-}
MOUNT=${MOUNT:-mount}
EFS=$ROOT/mnt/efs-system
LSDDIR=$EFS/lsd
TARGET=$LSDDIR/TestServer.jar
LIVE=$ROOT/lsd/TestServer.jar
JAR_CKSUM="@JAR_CKSUM@"
# Earlier test builds this card may replace or remove. A jar you built
# yourself is recognized by the marker below instead.
OLD_CKSUMS="134316928 25015|1600645946 37597|860323668 37386|1244262598 37386|3873138439 39235|176565670 65783|1332584532 65205|65765883 65110|3068110448 64723|2817642528 65201|1614746317 65130|3966562595 66905|2793235052 67333|2777218318 67350|2428946064 68296|3969618679 68344|2631443616 69152|2811709796 69270|1062943710 69314"
MARKER=de/audi/tghu/tone/hmi/evolution/VolumeBarPopin.class
LSDSH_CKSUM="2198181431 7675"
JXE_SIZE=13881111

SHOW=${SHOW:-$SDPATH/bin/showScreen}

# Full-screen status message on the MMI (blocks until a button is pressed).
screen() {
  $SHOW $SDPATH/lib/$1.png
}

# End of every card: close the log, put the SD card back to read-only so
# everything is flushed, then show the result screen.
finish() {
  exec >/dev/null 2>&1
  $MOUNT -ur $SDPATH
  screen $1
  exit $2
}

# true if the file is any volume bar version (current or older)
ours() {
  c=$(ck $1)
  [ "$c" = "$JAR_CKSUM" ] && return 0
  case "|$OLD_CKSUMS|" in
    *"|$c|"*) return 0 ;;
  esac
  # The jar is stored uncompressed, so its class names are plain text.
  # If grep can't handle the binary file, this fails and nothing is touched.
  grep -q "$MARKER" "$1" 2>/dev/null
}

ck() {
  # prints "crc size" of a file
  set -- $(cksum "$1" 2>/dev/null)
  echo "$1 $2"
}

stock_firmware() {
  [ "$MUVER" = "MMI3GH" ] || { echo "not an MMI 3G High (MUVER=$MUVER)"; return 1; }
  [ "$(ck $LSDDIR/lsd.sh)" = "$LSDSH_CKSUM" ] || { echo "lsd.sh is not the stock K0133 one"; return 1; }
  ls -l $ROOT/mnt/ifs-root/lsd/lsd.jxe | grep -q " $JXE_SIZE " || { echo "lsd.jxe is not the K0133 one"; return 1; }
  return 0
}
