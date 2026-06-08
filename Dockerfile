FROM eclipse-temurin:17
WORKDIR /app
COPY . .
RUN javac VideoStreamingServer.java
CMD ["java", "VideoStreamingServer"]
