## Building Freenet from source: Short version

Install Gradle:
For Debian Wheezy `gradle`.
For CentOS, `sudo yum install gradle` will install all of the above.

Then just execute `gradle jar`.

You can run your version of Freenet by stopping your node, copying `build/libs/freenet.jar` into your Freenet folder and starting your node again.

To override values of variables set in build.gradle by putting them into the file gradle.properties in the format `variable = value`.

Mine looks like the following:

org.gradle.parallel = true
org.gradle.daemon = true
org.gradle.jvmargs=-Xms256m -Xmx1024m
org.gradle.configureondemand=true

tasks.withType(Test)  {
  maxParallelForks = Runtime.runtime.availableProcessors()
}
