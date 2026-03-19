FROM maven:3.9.12-eclipse-temurin-21-alpine AS building

WORKDIR /workspace

COPY settings.xml /usr/share/maven/conf/settings.xml
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests clean package

FROM eclipse-temurin:21-jre-jammy AS running

WORKDIR /opt

RUN apt-get update \
 && apt-get install -y --no-install-recommends bash \
 && rm -rf /var/lib/apt/lists/*

ENV JAVA_OPTS="-server -Xms256m -XX:MaxRAMPercentage=60" \
    JAVA_AGENT=""

COPY --from=building /workspace/target/springai-agent-platform-0.0.1-SNAPSHOT.jar /opt/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS $JAVA_AGENT -jar /opt/app.jar"]
