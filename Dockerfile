FROM gradle:jdk8 as builder

COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle build

FROM openjdk:8-jdk-alpine
COPY --from=builder /home/gradle/src/build/distributions/spldiscord.tar /app/
WORKDIR /app
RUN tar -xvf spldiscord.tar
WORKDIR /app/spldiscord
VOLUME /app/volume
# Snowflake IDs for all of these
ENV REQUEST_CHANNEL=0
ENV MODS_CHANNEL=0
ENV GUILD=0
ENV APPROVER_ROLE=0
# Directory for the serverpacklocator - typically this will be the servermods folder under the minecraft server directory
ENV OUTPUT_DIR=/app/volume/
CMD bin/spldiscord