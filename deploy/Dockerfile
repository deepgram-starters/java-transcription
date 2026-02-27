# Stage 1: Build custom Caddy with rate limiting module
FROM golang:1.24 AS caddy-builder
WORKDIR /build
RUN go install github.com/caddyserver/xcaddy/cmd/xcaddy@latest
RUN xcaddy build --with github.com/mholt/caddy-ratelimit
# Binary output: /build/caddy

# Stage 2: Build frontend
FROM node:24-slim AS frontend-builder
RUN corepack enable
WORKDIR /build
COPY frontend/package.json frontend/pnpm-lock.yaml ./frontend/
RUN cd frontend && pnpm install --frozen-lockfile
COPY frontend/ ./frontend/
RUN cd frontend && pnpm build
# Inject Caddy template directive for subpath base path
RUN sed -i 's|<head>|<head>\n  <base href="{{ .Req.Header.Get "X-Base-Path" }}">|' ./frontend/dist/index.html

# Stage 3: Build Java backend
FROM maven:3.9-eclipse-temurin-21 AS java-builder
WORKDIR /build
COPY pom.xml ./
RUN mvn dependency:resolve -q
COPY src/ ./src/
COPY deepgram.toml sample.env ./
RUN mvn clean package -q

# Stage 4: Runtime
FROM eclipse-temurin:21-jre-alpine

# Copy custom Caddy binary
COPY --from=caddy-builder /build/caddy /usr/bin/caddy

WORKDIR /app

# Copy Java JAR and config
COPY --from=java-builder /build/target/java-transcription-1.0.0-shaded.jar ./
COPY --from=java-builder /build/deepgram.toml /build/sample.env ./

# Copy built frontend
COPY --from=frontend-builder /build/frontend/dist/ ./frontend/dist/

# Copy deployment files
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY deploy/start.sh ./deploy/start.sh
RUN chmod +x ./deploy/start.sh

ENV BACKEND_CMD="java -jar /app/java-transcription-1.0.0-shaded.jar"
EXPOSE 8080

CMD ["/app/deploy/start.sh"]
