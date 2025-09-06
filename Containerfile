# syntax=docker/dockerfile:1
FROM docker.io/library/maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests && cp target/*.jar app.jar

FROM docker.io/library/eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
