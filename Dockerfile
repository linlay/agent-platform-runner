FROM maven:3.9.12-eclipse-temurin-21-alpine AS building

WORKDIR /workspace

COPY settings.xml /usr/share/maven/conf/settings.xml
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests clean package

FROM eclipse-temurin:21-jre-jammy AS running

WORKDIR /opt/app

RUN apt-get update \
 && apt-get install -y --no-install-recommends bash \
 # && apt-get install -y --no-install-recommends python3 python3-venv python3-pip \
 # && python3 -m venv /opt/venv \
 # && /opt/venv/bin/pip install --no-cache-dir --upgrade pip \
 # && /opt/venv/bin/pip install --no-cache-dir \
 #    "pillow>=10.0.0" \
 #    "imageio>=2.31.0" \
 #    "imageio-ffmpeg>=0.4.9" \
 #    "numpy>=1.24.0" \
 && rm -rf /var/lib/apt/lists/*

ENV JAVA_OPTS="-server -Xms256m -XX:MaxRAMPercentage=60" \
    JAVA_AGENT="" \
    SPRING_CONFIG_ADDITIONAL_LOCATION="optional:file:/opt/application.yml" \
    AGENT_AGENTS_EXTERNAL_DIR="/opt/agents" \
    AGENT_VIEWPORTS_EXTERNAL_DIR="/opt/viewports" \
    AGENT_TOOLS_EXTERNAL_DIR="/opt/tools" \
    AGENT_SKILLS_EXTERNAL_DIR="/opt/skills" \
    MEMORY_CHATS_DIR="/opt/chats"

COPY --from=building /workspace/target/springai-agent-platform-0.0.1-SNAPSHOT.jar /opt/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS $JAVA_AGENT -jar /opt/app.jar"]
