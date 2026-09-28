#!/bin/sh
# A display, a window onto it, then the server.
#
# The browser runs with a screen rather than headless, because passing an anti
# robot check is something a human does with their eyes and one click. Xvfb gives
# it a screen nobody watches, x11vnc shares that screen, and noVNC turns it into a
# page you can open. Set LBC_VIEWER=off to skip all three.
set -e

# A persistent profile remembers which machine last used it, by hostname, and a
# container gets a new hostname every time it is recreated. Chromium then finds a
# lock it believes another machine still holds, and refuses to open the profile at
# all. Nothing else is running yet at this point, so the lock cannot be anything
# but stale. Without this, the second "docker compose up" never starts a browser.
rm -f "${LBC_PROFILE:-/profile}"/Singleton* 2>/dev/null || true

if [ "${LBC_VIEWER:-on}" != "off" ]; then
    rm -f /tmp/.X99-lock
    Xvfb "$DISPLAY" -screen 0 1440x900x24 -nolisten tcp >/dev/null 2>&1 &
    i=0
    while [ ! -e "/tmp/.X11-unix/X${DISPLAY#:}" ] && [ $i -lt 60 ]; do
        i=$((i + 1))
        sleep 0.25
    done

    # No password means no password: the port is bound to localhost in the compose
    # file for that reason. Whoever opens it can drive a browser holding your
    # session, so set LBC_VNC_PASSWORD before exposing it anywhere else.
    if [ -n "$LBC_VNC_PASSWORD" ]; then
        mkdir -p /tmp/vnc
        x11vnc -storepasswd "$LBC_VNC_PASSWORD" /tmp/vnc/passwd >/dev/null 2>&1
        x11vnc -display "$DISPLAY" -forever -shared -rfbauth /tmp/vnc/passwd \
            -quiet -localhost >/dev/null 2>&1 &
    else
        x11vnc -display "$DISPLAY" -forever -shared -nopw \
            -quiet -localhost >/dev/null 2>&1 &
    fi

    websockify --web=/usr/share/novnc 7900 localhost:5900 >/dev/null 2>&1 &
fi

exec java $JAVA_OPTS -jar /app/lbc.jar "$@"
