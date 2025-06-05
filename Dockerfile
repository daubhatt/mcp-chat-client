# Use the Eclipse temurin alpine official image
# https://hub.docker.com/_/eclipse-temurin
FROM eclipse-temurin:21-jdk-alpine

# Create and change to the app directory.
WORKDIR /app

# Copy local code to the container image.
COPY . ./

# Build the app.
RUN ./mvnw -B -DskipTests clean package

# Run the app by dynamically finding the JAR file in the target directory
CMD ["java","-Xms512m", "-Xmx1024m", "-jar", "target/mcp-chat-client-1.0.0.jar"]