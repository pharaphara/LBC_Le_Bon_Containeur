# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Dependencies first, so editing a source file does not refetch the internet.
RUN apt-get update && apt-get install -y --no-install-recommends zip \
 && rm -rf /var/lib/apt/lists/*
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/lbc-*.jar extract --destination /out \
 && mv /out/lbc-*.jar /out/lbc.jar \
 # Playwright's bundle carries a Node runtime for five platforms. This image runs
 # one of them, and the other four are 155 MB of nothing.
 && zip -q -d /out/lib/driver-bundle-*.jar \
      'driver/mac/*' 'driver/mac-arm64/*' 'driver/win32_x64/*' 'driver/linux-arm64/*'

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre-noble

ENV DISPLAY=:99 \
    PLAYWRIGHT_BROWSERS_PATH=/browsers \
    LBC_PROFILE=/profile \
    LBC_DATA=/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=70"

# xvfb, x11vnc, novnc, websockify: a display, and a window onto it, because a
# human has to be able to look at the page and click once when a check appears.
RUN apt-get update && apt-get install -y --no-install-recommends \
      xvfb x11vnc novnc websockify \
      fonts-liberation fonts-noto-color-emoji ca-certificates tini \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /out /app

# Chromium, plus the system libraries it wants, installed by Playwright's own cli.
RUN java -cp "/app/lib/*" com.microsoft.playwright.CLI install --with-deps chromium \
 && rm -rf /var/lib/apt/lists/* /root/.cache

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
# Uid 1000 to match the first user on most hosts, so a bind mounted volume is
# writable without a chown dance. The base image may already own that uid under
# another name, which is fine: what matters is the number and a writable home.
RUN chmod +x /usr/local/bin/entrypoint.sh \
 && (id -u 1000 >/dev/null 2>&1 || useradd -u 1000 -M lbc) \
 && mkdir -p /profile /data /home/lbc \
 && chown -R 1000:1000 /profile /data /browsers /home/lbc

ENV HOME=/home/lbc
USER 1000
EXPOSE 8788 7900
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8788' || exit 1
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]
