FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test
FROM eclipse-temurin:17-jre
RUN mkdir -p /app/data && chown -R 10001:10001 /app
USER 10001:10001
WORKDIR /app
COPY --from=build /app/build/libs/tooja.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
