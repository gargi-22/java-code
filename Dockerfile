FROM eclipse-temurin:17

# Install OpenCV
RUN apt-get update && apt-get install -y \
    libopencv-dev \
    python3-opencv \
    && apt-get clean

# Set OpenCV library path
ENV LD_LIBRARY_PATH=/usr/lib/jni

WORKDIR /app
COPY . .

# Compile with OpenCV
RUN javac -cp /usr/share/java/opencv.jar VideoStreamingServer.java

CMD ["java", "-Djava.library.path=/usr/lib/jni", "-cp", "/usr/share/java/opencv.jar:.", "VideoStreamingServer"]
