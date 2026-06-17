#!/bin/bash
export ANDROID_HOME=/home/user/android-sdk
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

cd /mnt/d/Tradar
echo "SDK: $ANDROID_HOME"
ls $ANDROID_HOME/platforms/
echo "Building..."
./gradlew assembleRelease 2>&1
echo "Exit code: $?"
