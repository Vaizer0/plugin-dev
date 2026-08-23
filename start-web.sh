#!/data/data/com.termux/files/usr/bin/sh
cd /data/data/com.termux/files/home/plugin-dev || exit 1
PIDFILE=/data/data/com.termux/files/usr/tmp/opencode/webserver.pid
if [ -f "$PIDFILE" ]; then
  OLDPID=$(cat "$PIDFILE")
  if [ -n "$OLDPID" ] && kill -0 "$OLDPID" 2>/dev/null; then
    kill "$OLDPID" 2>/dev/null
    sleep 2
    kill -9 "$OLDPID" 2>/dev/null
  fi
fi
setsid nohup ./web/build/install/web/bin/web </dev/null >> /data/data/com.termux/files/usr/tmp/opencode/webserver.log 2>&1 &
echo $! > "$PIDFILE"
echo "launched pid $(cat "$PIDFILE")"
