#!/bin/bash
# QNX 6.3's ksh is pdksh-based and predefines these aliases. A function
# named like one of them breaks at parse time ("syntax error: `(' unexpected").
ALIASES="autoload functions hash history integer local login nohup r stop suspend type true false"
bad=0
for f in common.sh install.sh remove.sh report.sh firmware.sh; do
  for fn in $(grep -oE '^[A-Za-z_][A-Za-z0-9_]*\(\)' $f | tr -d '()'); do
    for a in $ALIASES; do
      [ "$fn" = "$a" ] && { echo "LINT FAIL: $f defines function '$fn' (pdksh alias)"; bad=1; }
    done
  done
  # also never call a bare alias name as a command
  for a in $ALIASES; do
    hits=$(grep -nE "(^|[;&|{(]|then|else|do)[[:space:]]*$a([[:space:]]|$)" $f | grep -vE '^[0-9]+:[[:space:]]*#')
    [ -n "$hits" ] && { echo "LINT FAIL: $f uses alias '$a': $hits"; bad=1; }
  done
done
[ $bad = 0 ] && echo "LINT OK"
exit $bad
