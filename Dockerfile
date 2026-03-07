FROM amazoncorretto:25-alpine as runner
WORKDIR /app
COPY ./build/libs/ZootPlusBackend*.jar /app/app.jar
EXPOSE 7000-9000
ENTRYPOINT ["java", "-jar", "app.jar", "${JAVA_OPTS}"]
