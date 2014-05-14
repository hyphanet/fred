#!/usr/bin/env sh

# we want to abort on first error
set -e

# build freenet-ext.jar
ant -f contrib/freenet-ext/build.xml

# grab some build dependencies
wget http://bouncycastle.org/download/bcprov-jdk15on-150.jar -O lib/bcprov.jar

# build Fred
ant -f build-clean.xml

