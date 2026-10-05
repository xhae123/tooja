FROM eclipse-temurin:17-jre
RUN mkdir -p /app/data && chown -R 10001:10001 /app
WORKDIR /app
COPY --chown=10001:10001 app.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
