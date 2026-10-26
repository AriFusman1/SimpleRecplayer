# PCAP Player Simple - one image: React frontend + Vert.x backend in a single jar.

# ---------- 1. Build the React frontend ----------
FROM node:20.18.0-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# vite.config.ts writes the output to ../backend/src/main/resources/webroot
RUN npm run build

# ---------- 2. Build the Java fat jar ----------
FROM maven:3.9.9-eclipse-temurin-17 AS backend
WORKDIR /build/backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /build/backend/src/main/resources/webroot ./src/main/resources/webroot
RUN mvn -B -q package

# ---------- 3. Runtime image ----------
FROM eclipse-temurin:17.0.12_7-jre
WORKDIR /app
COPY --from=backend /build/backend/target/pcap-player-simple.jar /app/app.jar

# OpenShift runs the container with a random user id that belongs to group 0 (root group).
# Giving group 0 the same rights as the owner makes /app usable for that user.
# Temp files (uploads, Vert.x cache) go to /tmp, which every user can write.
RUN chgrp -R 0 /app && chmod -R g=u /app
USER 1001

ENV HTTP_PORT=8080 \
    TEMP_DIR=/tmp/pcap-player
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
