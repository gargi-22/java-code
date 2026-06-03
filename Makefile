
OPENCV_ROOT := C:/Userspace/opencv/opencv
OPENCV_JAR := $(OPENCV_ROOT)/build/java/opencv-4120.jar
OPENCV_LIB := $(OPENCV_ROOT)/build/java/x64

ifeq ($(OS),Windows_NT)
CP := .;$(OPENCV_JAR)
else
CP := .:$(OPENCV_JAR)
endif

%.class : %.java
	javac -cp "$(CP)" $<

all : VideoStreamingServer.class VideoStreamingClient.class

clean :
	rm -f -v *.class

runserver : VideoStreamingServer.class
	java -Djava.library.path="$(OPENCV_LIB)" -cp "$(CP)" VideoStreamingServer sample.mp4 8080

runclient : VideoStreamingClient.class
	java -cp "$(CP)" VideoStreamingClient localhost 8080



