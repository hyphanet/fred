# __Freenet__ ![logo][logo]

[![Chat](https://img.shields.io/badge/chat-on_freenode-blue.svg)](http://webchat.freenode.net?randomnick=1&channels=%23freenet&prompt=1)
[![Bugtracker](https://img.shields.io/badge/bugs-mantishub-blue.svg)](https://freenet.mantishub.io)
[![Build Status](https://travis-ci.org/freenet/fred.svg?branch=next)](https://travis-ci.org/freenet/fred)
[![Build status windows](https://ci.appveyor.com/api/projects/status/r34ek9a06wdrahfn?svg=true)](https://ci.appveyor.com/project/skydrome/fred)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=skydrome_fred&metric=coverage)](https://sonarcloud.io/dashboard?id=skydrome_fred)

> Fred stands for Freenet REference Daemon

Freenet is a platform for censorship-resistant communication and publishing. It is peer-to-peer
software which provides a distributed, encrypted, decentralized datastore. Websites and applications
providing things like forums and chat are built on top of it.

## Building

We've included the [Gradle Wrapper][gradle_wrapper] as recommended by the Gradle project.
We've [configured it](gradle/wrapper/gradle-wrapper.properties) to [verify the checksum][gradle_verify]
of the archive it downloads from `https://services.gradle.org`.
If you trust the version we've committed you can build immediately:

#### with command-line interface:
    git clone https://github.com/freenet/fred.git
    cd fred
    ./gradlew jar

## Testing
You can view html test reports in the `build/reports` directory

To run all unit tests:

    ./gradlew test

You can run specific tests with a filter similar to the following:

    ./gradlew test --tests *M3UFilterTest

Or to exclude some tests from being run:

    ./gradlew test -PexcludeTests="freenet.client.async.SplitFileFetcherStorageTest,Test2,..."

### Additional Tests

    jacocoTestReport - Generates a code coverage report
    pmdMain - Runs PMD source code analyzer
    spotbugsMain - Runs SpotBugs byte-code analyzer

### Run your changes
To test your version of Freenet, build it with `./gradlew jar`,
stop your node, replace `freenet.jar` in your
Freenet directory with `build/libs/freenet.jar`, and start your node again.

To override values set in `build.gradle` add or edit the existing values from 
`gradle.properties` in [the format][gradle_config] `variable = value`. For instance:
```ini
org.gradle.daemon   = true
org.gradle.jvmargs  = -Xmx2g "-XX:MaxMetaspaceSize=512m"
org.gradle.caching  = false
targetJavaVersion   = 11
parallelTests       = true
```

## Getting Involved
* See our [Guide to Contributing](CONTRIBUTING.md)
* Open issues: https://freenet.mantishub.io/view_all_bug_page.php?filter=5cb54b4ec29b7
* Project wiki: https://github.com/freenet/wiki/wiki
* Mailing list: [devl@freenetproject.org][mailinglist]
    * archive: https://www.mail-archive.com/devl@freenetproject.org
* IRC: #freenet on chat.freenode.net

## Licensing
Freenet is licensed under the GPL, version 2 or later. We use some code under the
Apache license version 2 (mostly apache commons stuff), and some modified BSD code (Mantissa).
All of which is compatible with the GPL, although arguably ASL2 is only compatible with GPL3.
Some plugins are GPL3.
See [LICENSE](LICENSE)


[logo]: https://upload.wikimedia.org/wikipedia/commons/thumb/8/8d/Freenet_logo.svg/50px-Freenet_logo.svg.png
[gradle_wrapper]: https://docs.gradle.org/5.3/userguide/gradle_wrapper.html
[gradle_verify]:  https://docs.gradle.org/5.3/userguide/gradle_wrapper.html#sec:verification
[gradle_config]:  https://docs.gradle.org/5.3/userguide/build_environment.html#sec:gradle_configuration_properties
[mailinglist]: https://ml.freenetproject.org/v1
