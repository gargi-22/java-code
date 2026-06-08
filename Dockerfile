FROM eclipse-temurin:17

# Install dependencies
RUN apt-get update && apt-get install -y \
    wget \
    libopencv-java \
    && apt-get clean

WORKDIR /app
COPY . .

# Compile with OpenCV Java jar
RUN OPENCV_JAR=$(find / -name "opencv*.jar" 2>/dev/null | head -1) && \
    echo "Found jar: $OPENCV_JAR" && \
    javac -cp "$OPENCV_JAR" VideoStreamingServer.java

# Run with OpenCV
CMD OPENCV_JAR=$(find / -name "opencv*.jar" 2>/dev/null | head -1) && \
    java -Djava.library.path=/usr/lib/jni \
    -cp "$OPENCV_JAR:." \
    VideoStreamingServer
