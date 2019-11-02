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
CMD bin/spldiscord