ARG JAVA_IMAGE
FROM ${JAVA_IMAGE}
RUN mkdir -p /app && chown 10002:10002 /app
WORKDIR /app
COPY --chown=10002:10002 app.jar /app/app.jar
USER 10002:10002
ENTRYPOINT ["java","-XX:MaxRAMPercentage=55","-XX:+ExitOnOutOfMemoryError","-Duser.timezone=UTC","-jar","/app/app.jar"]
