FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY service/pom.xml service/pom.xml
COPY service/src service/src
COPY service/config service/config
WORKDIR /build/service
RUN mvn -B verify -Pstrict -DskipTests=false

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/service/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
