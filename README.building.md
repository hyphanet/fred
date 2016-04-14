## Building Freenet from source: Short version

Install junit 4, Apache ant and Hamcrest:
For Debian Wheezy these are `junit4`, `ant`, `ant-optional` and `libhamcrest-java`.
For CentOS, `sudo yum install ant-junit` will install all of the above.

Freenet also requires `freenet-ext.jar` in the `lib/freenet/` directory. To get it, [download](https://downloads.freenetproject.org/latest/freenet-ext.jar) or copy from the freenet folder of an existing installation or build the `contrib/` submodule.

Then just execute `ant`.

You can run your version of Freenet by stopping your node, copying `dist/freenet.jar` into your Freenet folder and starting your node again.


To override values of variables set in build.xml by putting them into the file override.properties in the format `variable = value`. To build Freenet without running the tests, use `ant -Dtests.skip=true`.


If sufficient packages for the dependencies are not available, put these jars into the `lib/` directory:

* `junit4.jar` and `hamcrest-core.jar`: See the [download instructions](https://github.com/junit-team/junit/wiki/Download-and-Install)
* `bcprov-jdk15on-154.jar`: Download Bouncy Castle 1.54: `wget https://www.bouncycastle.org/download/bcprov-jdk15on-154.jar`


## Building Freenet from source: Using Eclipse

You may want to use the command line git client, as people have sometimes had problems with the egit plugin for Eclipse.

#### Eclipse 4 ####

Create a new project via the new _Java Project from Existing Ant Build File_, selecting the _build.xml_ in the local cloned repo. This will create a project _freenet-autodep_. Copy the _build-clean.xml_ to the Eclipse project folder.

_Configure the Build Path_, removing the obsolete JRE_LIB link and 
* _Add Library_ choosing a Java 7  _JRE System Library_
* _Add Library_, choosing _JUnit_
* _Add External Archive..._ lib/freenet/freenet-ext.jar
* _Add External Archive..._ lib/bcprov.jar


#### Older versions of Eclipse ####

You may need to add the two jars, and junit 4, to the build path for the project, although the .project might help with this.

### Building Freenet from source: Caveats

Don't use build-clean.xml, or call "ant distclean". This will cause problems. In particular it may delete the GWT-generated javascript in src/freenet/clients/http/staticfiles/freenetjs/ . If this happens just checkout that folder again, or do "git reset --hard" to reset the whole project. Note that the generated javascript isn't actually used unless web-pushing is enabled in the config, but it is needed for building Freenet.


## Building Freenet from source: Long version

These are instructions on how to rebuild Freenet completely from source.

It is difficult for everyone to build all components, so the default Freenet
source package ships with some pre-compiled binaries. However, this means that
users need to trust that these binaries haven't been compromised.

For the careful/paranoid, we offer the option of building these binaries yourself, so
that this extra trust is not necessary[1]. Unfortunately, this involves more
effort than the default build path; help in easing this would be appreciated.

### Considerations

(Properties can be set in override.properties, similar to build.properties)

A. The build scripts need to know where the contrib jars are. By default, they
look in lib/freenet; you can override this by setting `lib.contrib.dir` - see
build.properties for more details. Similarly, you will need to tell build.xml
where the GWT jars are, see the "gjs.lib.jars" variable in build.properties;
you need to put the jars in lib/ and edit override.properties to include
gjs.lib.jars= [ list of jar names for GWT within lib/ ]

B. You may need to install extra Java libraries. These are listed in build.xml,
in the <path> elements. If you already have these installed, but ANT can't find
them, you can try setting the "java.class.dirs.user" property.

B.1. If you're doing a clean-build for security reasons, then you'll want to
clean-build these libraries too. Google's source package for GWT actually uses
many binary components during the build process, which makes a clean-build next
to impossible; however (e.g.) Debian's GWT source package[2] is pure source.

B.2. It's also a good idea to make sure you received the source code correctly.
Most modern package managers (e.g. APT) have signature verification, or you can
try HTTPS (e.g. logged in on googlecode).

C. There is a good deal of native code in freenet-ext.jar that won't be rebuilt
by our automatic build-scripts. You'll need to do this manually; they are: fec,
NativeBigInteger, NativeThread, win_wrapper and wrapper. This is probably the
most tedious step and needs to be done *before* the below command.

### Clean-building

The pre-compiled components are freenet-ext.jar (ext) and the GWT-generated
javascript (gjs). The former is a separate package ("contrib"), whereas the
latter is strictly contained within this package.

To use the build scripts, you need to install ant. To compile all of the above
completely from source, the recommended method is to use build-clean.xml:

    $ ... # retrieve and build external packages (e.g. contrib)
    $ ... # set build properties
    $ ant -f build-clean.xml dist

NOTE: the default checkout of this package contains some pre-built javascript;
running `dist` will remove it and re-build it from source. You can also remove
it separately by running the `clean-gjs` target.

[1] However, you still need to trust your compiler and operating system; see
Ken Thompson's "Reflections on Trusting Trust".
[2] http://packages.debian.org/source/sid/gwt

## Verify-build script

See README.md in the Maintenance scripts repository. The verify-build script 
will check a pre-built binary against the source code, but needs some work to
configure, as you need e.g. to have the same version of javac as it was built
with.

Get it from the maintenance scripts repo:

    git clone git@github.com:freenet/scripts.git
