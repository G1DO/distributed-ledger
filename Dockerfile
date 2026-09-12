FROM maven:3.9-eclipse-temurin-25 AS build
# The wrapper's pinned checksum is for the ZIP; without unzip it switches to TAR.GZ.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && apt-get clean
WORKDIR /build
COPY service/pom.xml service/pom.xml
COPY service/mvnw service/mvnw
COPY service/.mvn service/.mvn
COPY service/src service/src
COPY service/config service/config
WORKDIR /build/service
RUN ./mvnw -B verify -Pstrict -DskipTests=false -DskipITs

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/service/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
