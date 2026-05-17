# syntax=docker/dockerfile:1.7

# ---------- Stage 1: build the Kotlin/JS + Tailwind/Sass distribution ----------
FROM eclipse-temurin:17-jdk-jammy AS build

# Node is required by the Tailwind build step wired into Gradle.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
 && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /src

# Prime the Gradle and npm caches first so source-only edits do not bust them.
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ktlint.gradle sass.gradle tailwind.gradle ./
COPY gradle ./gradle
COPY package.json package-lock.json ./
RUN ./gradlew --version >/dev/null \
 && npm ci

# Copy module build scripts before sources, again for caching.
COPY app/build.gradle.kts ./app/
COPY libs ./libs
COPY tools ./tools
COPY tailwind.config.js postcss.config.js ./

# Now the actual sources.
COPY src ./src
COPY app/src ./app/src

# Production browser distribution. Output lands at /src/build/distributions
RUN ./gradlew --no-daemon -Dorg.gradle.parallel=false browserDistribution \
 && ls -la build/distributions

# ---------- Stage 2: PocketBase runtime ----------
FROM alpine:3.20 AS runtime

ARG PB_VERSION=0.38.1
ARG TARGETARCH

RUN apk add --no-cache ca-certificates unzip curl \
 && case "${TARGETARCH}" in \
      amd64) PB_ARCH=amd64 ;; \
      arm64) PB_ARCH=arm64 ;; \
      *) echo "Unsupported arch: ${TARGETARCH}" && exit 1 ;; \
    esac \
 && curl -fsSL -o /tmp/pb.zip \
      "https://github.com/pocketbase/pocketbase/releases/download/v${PB_VERSION}/pocketbase_${PB_VERSION}_linux_${PB_ARCH}.zip" \
 && unzip /tmp/pb.zip -d /usr/local/bin \
 && rm /tmp/pb.zip \
 && chmod +x /usr/local/bin/pocketbase \
 && apk del unzip curl

WORKDIR /pb

# Static site is served from pb_public; JS hooks and schema migrations are
# version-controlled alongside the Dockerfile.
COPY --from=build /src/build/distributions/ /pb/pb_public/
COPY server/pb_hooks /pb/pb_hooks
COPY server/pb_migrations /pb/pb_migrations

EXPOSE 8090
VOLUME ["/pb/pb_data"]

ENTRYPOINT ["/usr/local/bin/pocketbase"]
CMD ["serve", "--http=0.0.0.0:8090", "--dir=/pb/pb_data", "--publicDir=/pb/pb_public", "--hooksDir=/pb/pb_hooks", "--migrationsDir=/pb/pb_migrations"]
