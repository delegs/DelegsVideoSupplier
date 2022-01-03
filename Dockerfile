FROM tomcat:8.5.43-jdk8-openjdk
MAINTAINER delegs-team <github@delegs.de>

# setup file structure
RUN mkdir -p /pfad/zum/upload/verzeichnis

# install ffmpeg
RUN mkdir -p /pfad/zum/ffmpeg/verzeichnis
RUN apt update && apt upgrade -y && apt install -y ffmpeg
RUN ln -s $(which ffmpeg) /pfad/zum/ffmpeg/verzeichnis/ffmpeg
RUN ln -s $(which ffprobe) /pfad/zum/ffmpeg/verzeichnis/ffprobe

# deploy the war
COPY target/DelegsVideoSupplier.war /usr/local/tomcat/webapps/DelegsVideoSupplier.war

RUN /usr/local/tomcat/bin/startup.sh

