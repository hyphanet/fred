#!/bin/sh

cp gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties.orig
sed -i 's,^distributionUrl=.*,distributionUrl=../../lib/gradle-8.14.5-bin.zip,' gradle/wrapper/gradle-wrapper.properties
cp build.gradle build.gradle.orig
sed -i '/com.android.tools.build/d' build.gradle

./gradlew jar --offline --no-daemon
RES=$?

mv gradle/wrapper/gradle-wrapper.properties.orig gradle/wrapper/gradle-wrapper.properties
mv build.gradle.orig build.gradle

# propagate the error from the gradle invocation
test $RES -eq 0
