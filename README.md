[![Build Status](https://travis-ci.org/freenet/fred.svg?branch=next)](https://travis-ci.org/freenet/fred)
[![Coverity status](https://scan.coverity.com/projects/2316/badge.svg?flat=1)](https://scan.coverity.com/projects/freenet-fred)

# Freenet

Freenet is a platform for censorship-resistant communication and publishing. It is peer-to-peer
software which provides a distributed, encrypted, decentralized datastore. Websites and applications
providing things like forums and chat are built on top of it.

Fred stands for Freenet REference Daemon.

## Building

We've included the [Gradle Wrapper](https://docs.gradle.org/3.2/userguide/gradle_wrapper.html) as
recommended by the Gradle project. If you trust the version we've committed you can build
immediately:

#### POSIX / Windows PowerShell:

    $ ./gradlew jar

#### Windows cmd:

    > gradlew jar

We've [configured it](gradle/wrapper/gradle-wrapper.properties) to [verify the checksum](https://docs.gradle.org/3.2/userguide/gradle_wrapper.html#sec:verification)
of the archive it downloads from `https://services.gradle.org`.

## Testing

### Run Tests

To run all unit tests, use

    ./gradlew --parallel test

You can run specifics tests with a test filter similar to the following:

    ./gradlew --parallel test --tests *M3UFilterTest

TODO: how to run integration tests.

### Run your changes as node

To test your version of Freenet, build it with ,./gradlew jar`,
stop your node, replace `freenet.jar` in your
Freenet directory with `build/libs/freenet.jar`, and start your node again.

To override values set in `build.gradle` put them into [the file](https://docs.gradle.org/3.2/userguide/build_environment.html)
`gradle.properties` in the format `variable = value`. For instance:

    org.gradle.parallel = true
    org.gradle.daemon = true
    org.gradle.jvmargs=-Xms256m -Xmx1024m
    org.gradle.configureondemand=true

    tasks.withType(Test)  {
      maxParallelForks = Runtime.runtime.availableProcessors()
    }

## Contributing

See our [contributor guidelines](CONTRIBUTING.md).

## Licensing
Freenet is under the GPL, version 2 or later - see LICENSE.Freenet. We use some
code under the Apache license version 2 (mostly apache commons stuff), and some
modified BSD code (Mantissa). All of which is compatible with the GPL, although
arguably ASL2 is only compatible with GPL3. Some plugins are GPL3.
