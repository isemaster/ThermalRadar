#!/bin/bash
cd /mnt/d/Tradar
export ANDROID_HOME=/home/user/android-sdk
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
./gradlew assembleRelease --no-daemon > /tmp/build.log 2>&1
echo "Exit code: $?"
