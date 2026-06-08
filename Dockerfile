FROM eclipse-temurin:17

# Install OpenCV Java bindings + native libs
RUN apt-get update && apt-get install -y \
    libopencv-java \
    libopencv4.5d-jni \
    && apt-get clean

WORKDIR /app
COPY . .

# Compile using the correct jar
RUN javac -cp "/usr/share/java/opencv4/opencv-4100.jar" VideoStreamingServer.java

# Run with native lib path
CMD java \
    -Djava.library.path=/usr/lib/x86_64-linux-gnu/jni \
    -cp "/usr/share/java/opencv4/opencv-4100.jar:." \
    VideoStreamingServer
