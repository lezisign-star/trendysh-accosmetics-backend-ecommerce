FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package && mv target/*.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/app.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
