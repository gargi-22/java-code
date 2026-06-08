FROM eclipse-temurin:17

RUN apt-get update && apt-get install -y \
    libopencv-java \
    && apt-get clean

WORKDIR /app
COPY . .

RUN javac -cp "/usr/share/java/opencv4/opencv-4100.jar" VideoStreamingServer.java

CMD java \
    -Djava.library.path=/usr/lib/jni:/usr/lib/x86_64-linux-gnu/jni:/usr/lib/x86_64-linux-gnu \
    -cp "/usr/share/java/opencv4/opencv-4100.jar:." \
    VideoStreamingServer
