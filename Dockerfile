FROM eclipse-temurin:17
COPY . /app
WORKDIR /app
RUN make
CMD ["java", "VideoStreamingServer"]