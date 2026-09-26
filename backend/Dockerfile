# Java 25 to match <maven.compiler.release>25</maven.compiler.release> in pom.xml (the previous
# JDK 21 image could not compile it). Debian/Ubuntu-based Temurin images rather than Alpine:
# they are published for arm64, which is what Oracle Cloud's free Ampere machines run.
FROM eclipse-temurin:25-jdk AS build
ARG MAVEN_VERSION=3.9.9
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
 && curl -fsSL https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    | tar xz -C /opt \
 && ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# Dependencies first, so a code-only change doesn't re-download them on every build.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser \
 && apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
ENV TZ=Asia/Kolkata
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
