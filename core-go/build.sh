#!/bin/sh
set -eu
cd "$(dirname "$0")"
export PATH="/usr/local/bin:$PATH:$(go env GOPATH)/bin"
: "${ANDROID_HOME:?Set ANDROID_HOME to Android SDK}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or newer}"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260821190718-4776eadac327
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260821190718-4776eadac327
go get golang.org/x/mobile@v0.0.0-20260821190718-4776eadac327
mkdir -p libs
gomobile bind -androidapi 21 -target android/arm64,android/arm,android/amd64 -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' -o libs/nexuscore.aar ./mobile
