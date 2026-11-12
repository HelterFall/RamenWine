#!/bin/sh
if [ "$#" -lt 1 ]; then
  echo "RamenWine: run-wine-command.sh requires a Wine program argument." >&2
  exit 64
fi

/usr/bin/box64 /usr/bin/wine "$@"
wine_status=$?

/usr/bin/box64 /usr/bin/wineserver -k
kill_status=$?
/usr/bin/box64 /usr/bin/wineserver -w
wait_status=$?

if [ "$kill_status" -ne 0 ]; then
  echo "RamenWine: wineserver -k returned $kill_status; waited for server shutdown." >&2
fi
if [ "$wait_status" -ne 0 ]; then
  echo "RamenWine: wineserver did not release its lock (exit $wait_status)." >&2
fi
if [ "$wine_status" -ne 0 ]; then
  exit "$wine_status"
fi
exit "$wait_status"
