# FROM clojure:temurin-21-tools-deps-jammy AS builder

# # Copying and building deps as a separate step in order to mitigate
# # the need to download new dependencies every build.
# COPY deps.edn /usr/src/app/deps.edn
# WORKDIR /usr/src/app
# RUN clojure -P
# COPY . /usr/src/app
# RUN clojure -T:build uber

# Using image without lein for deployment.
FROM amazoncorretto:21
LABEL maintainer="Tristan Nelson <thnelson@geisinger.edu>"

COPY target/app.jar /app/app.jar

EXPOSE 8888

CMD ["java", "-jar", "/app/app.jar"]
