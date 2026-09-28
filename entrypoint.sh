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

# Start Chromium the way a person would, then talk to it.
#
# This is the difference between a browser that announces being driven and one that
# does not. Letting Playwright launch the browser adds the automation flag, and
# navigator.webdriver comes back true. Starting an ordinary desktop browser with a
# debugging port open, and merely attaching to it, leaves it false. Measured side by
# side against a browser that has been reading this kind of site daily for months:
# webdriver false there, true here, and that was the only structural difference.
#
# Nothing is forged and nothing is hidden. A flag is simply not added.
if [ "${LBC_BROWSER:-attach}" = "attach" ] && [ -z "$LBC_CDP_URL_EXTERNAL" ]; then
    CHROME=$(ls -d /browsers/chromium-*/chrome-linux*/chrome 2>/dev/null | head -1)
    if [ -n "$CHROME" ]; then
        "$CHROME" \
            --remote-debugging-port=9222 \
            --remote-allow-origins=* \
            --user-data-dir="${LBC_PROFILE:-/profile}" \
            --lang="${LBC_LANG:-fr-FR}" \
            --accept-lang="${LBC_ACCEPT_LANG:-fr-FR,fr,en-US,en}" \
            --window-size=1440,900 \
            --no-sandbox \
            --disable-dev-shm-usage \
            --no-first-run \
            --no-default-browser-check \
            about:blank >/dev/null 2>&1 &
        i=0
        while [ $i -lt 60 ]; do
            if (exec 3<>/dev/tcp/127.0.0.1/9222) 2>/dev/null; then break; fi
            i=$((i + 1))
            sleep 0.5
        done
        export LBC_CDP_URL="${LBC_CDP_URL:-http://127.0.0.1:9222}"
    fi
fi

# What an operator needs to know in the first five seconds, including the one thing
# no software can do for them. Docker prints nothing from inside a container when
# it is started detached, so this lands in the logs: "docker compose logs lbc", or
# "docker compose up" without -d the first time.
VIEWER="${LBC_VNC_URL:-http://localhost:7900}"
MCP="http://localhost:${SERVER_PORT:-8788}/mcp"
if [ -s "${LBC_PROFILE:-/profile}/Default/Cookies" ]; then
    PROFILE_STATE="already used, so the fast path may already be open"
else
    PROFILE_STATE="brand new, so the data calls will be refused and the rendered
              pages read instead, about 35 ads per page"
fi
cat <<BANNER

  le bon container
  Il a la tete de l emploi.

  MCP       $MCP
  Browser   $VIEWER
  Profile   $PROFILE_STATE
  Driving   ${LBC_CDP_URL:-launched by the library, which announces automation}

  Open the browser address once and pass the anti robot check by hand, and sign in
  if you want to. Nothing here forges a fingerprint or buys a captcha, so that one
  click is the trade. The profile volume remembers it.

BANNER

exec java $JAVA_OPTS -jar /app/lbc.jar "$@"
