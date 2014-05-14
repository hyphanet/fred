#!/usr/bin/env sh

# we want to abort on first error
set -e

# build freenet-ext.jar
ant -f contrib/freenet-ext/build.xml

# grab some more dependencies
wget http://bouncycastle.org/download/bcprov-jdk15on-150.jar -O lib/bcprov.jar
wget 'http://search.maven.org/remotecontent?filepath=junit/junit/4.11/junit-4.11.jar' -O lib/junit.jar

# build Fred
ant -f build-clean.xml

