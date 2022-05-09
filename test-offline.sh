#!/bin/sh

cp gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties.orig
sed -i 's,^distributionUrl=.*,distributionUrl=../../lib/gradle-4.10.3-bin.zip,' gradle/wrapper/gradle-wrapper.properties
cp build.gradle build.gradle.orig
sed -i '/com.android.tools.build/d' build.gradle

./gradlew jar --parallel --offline --no-daemon test

mv gradle/wrapper/gradle-wrapper.properties.orig gradle/wrapper/gradle-wrapper.properties
mv build.gradle.orig build.gradle
