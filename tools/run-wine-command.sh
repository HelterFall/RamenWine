#!/bin/sh
if [ "$#" -lt 1 ]; then
  echo "RamenWine: run-wine-command.sh requires a Wine program argument." >&2
  exit 64
fi

# Capture a compact guest-side snapshot immediately before Wine starts. This
# gives one report the page size, memory pressure and process limits seen by
# the same PRoot guest that runs Box64/Wine.
printf 'RamenWine: wrapper pid=%s command=%s args=%s\n' "$$" "$1" "$*"
printf 'RamenWine: environment WINEPREFIX=%s WINEDEBUG=%s BOX64_LOG=%s BOX64_DLSYM_ERROR=%s\n' \
  "${WINEPREFIX:-<unset>}" "${WINEDEBUG:-<unset>}" \
  "${BOX64_LOG:-<unset>}" "${BOX64_DLSYM_ERROR:-<unset>}"
if command -v getconf >/dev/null 2>&1; then
  printf 'RamenWine: guest page size='
  getconf PAGESIZE 2>/dev/null || echo unavailable
else
  echo "RamenWine: getconf unavailable; see Android host page size in report."
fi
if [ -r /proc/meminfo ]; then
  grep -E '^(MemTotal|MemAvailable|MemFree|SwapTotal|SwapFree|CommitLimit|Committed_AS):' /proc/meminfo
fi
if [ -r /proc/self/status ]; then
  grep -E '^(Name|Pid|PPid|VmPeak|VmSize|VmRSS|VmData|VmStk|VmExe|VmLib|Threads|FDSize):' /proc/self/status
fi
if [ -r /proc/self/limits ]; then
  echo "RamenWine: guest process limits:"
  cat /proc/self/limits
fi

/usr/bin/box64 /usr/bin/wine "$@"
wine_status=$?
printf 'RamenWine: Wine command exit=%s\n' "$wine_status"

/usr/bin/box64 /usr/bin/wineserver -k
kill_status=$?
printf 'RamenWine: wineserver -k exit=%s\n' "$kill_status"
/usr/bin/box64 /usr/bin/wineserver -w
wait_status=$?
printf 'RamenWine: wineserver -w exit=%s\n' "$wait_status"

if [ "$wait_status" -ne 0 ]; then
  echo "RamenWine: wineserver did not release its lock; the setup prefix may remain busy." >&2
fi
if [ "$wine_status" -ne 0 ]; then
  exit "$wine_status"
fi
exit "$wait_status"
