FROM maven:3.8.1-jdk-11 as build

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline

COPY src/ /app/src/
RUN mvn clean package -DskipTests

FROM openjdk:11-jdk-slim

WORKDIR /app

COPY --from=build /app/target/java-worker-1-jar-with-dependencies.jar /app/java-worker-1.jar
COPY start.sh /app/start.sh
COPY JavaTestProject/ /app/JavaTestProject/


RUN chmod +x /app/start.sh

CMD ["/bin/bash", "/app/start.sh"]