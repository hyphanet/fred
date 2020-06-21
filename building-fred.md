# Building Fred without freenet-ext

This projects aims to have a straight forward build of Fred without much need for freenet-ext dependency.

The goal is to:

- Delegate most of the work to Gradle to avoid manual building of external dependencies.
- Eliminate unneeded or deprecated dependencies

![Building blocks](https://github.com/desyncr/fred/blob/mobile-node/devnotes/Freenet%20mNode.png?raw=true)

## How to build without freenet-ext

- Require packages directly from fred's gradle

```
    compile "org.bouncycastle:bcprov-jdk15on:1.59"
    compile "net.java.dev.jna:jna:4.2.2"
    compile "net.java.dev.jna:jna-platform:4.2.2"
    compile "tanukisoft:wrapper:3.2.3"
    compile "org.apache.commons:commons-compress:1.4.1"
```
    
Include jars directly:

```
    compile files("lib/mantissa-7.2.jar")
    compile files("lib/bitcollider-core.jar")
    compile files("lib/lzmajio.jar")
    compile files("lib/freenet-ext.jar")
```

Alternative:

- Build Fred without failing dependencies (so far bdb-je)

### Removed

- bdb-je
- db4o
- common-compress (load from gradle)
- wrapper (load from gradle)
- onionnetworks fec (bundled source code)

