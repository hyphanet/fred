next:

- 

1499:

# User changelog

This release mainly brings improvements in five areas:

- improved privacy protection
- optimized and fixed networking layer
- support for website authors
- better user experience
- clean ups and code purges

## Privacy

The privacy improvements are reduced pings and DNS lookups. These make
it less likely that a node gets detected by pings it sends for other
nodes that use DNS addresses to have permanent addresses. Also thanks
to Torusrxxx SSL support is updated and when setting up a node for
access via SSL, unencrypted HTTP links now get upgraded to secured
HTTPS automatically.

## Networking

The networking layer got a fix for very fast nodes. On these the bulk
queue could be starved when realtime requests were received faster
than they could be completed. Also bertm fixed bulk backoff getting
ignored.

Juiceman removed unnecessary boxing and unboxing to reduce CPU load
and Torusrxxx improved IPv6 handling.

## Website

Torusrxxx also added initial webp support (still todo: lossless and
animation) to the content sanitation (filters), so you can now use
webp images on freesites and depending on the study reduce the storage
by around 30%. Minimizing storage is more critical on Hyphanet
compared to the clearnet, so webp can improve performance for all
sites.

In addition to webp, Torusrxxx also added support for more CSS
keywords: border-{top,bottom}-{left,right}-radius, color-scheme,
dominant-baseline, margin-block, margin-block-end, margin-block-start,
margin-bottom, margin-inline, margin-inline-end, margin-inline-start,
margin-right, math-style, padding-block, padding-block-end,
padding-block-start, padding-inline, padding-inline-end,
padding-inline-start, padding-right, padding-right, padding-top,
padding-top, scroll-behavior, scroll-snap-align, scroll-snap-stop,
scroll-snap-type, text-combine-upright, text-decoration-thickness,
text-orientation, text-underline-offset.

And font settings: monospace, system-ui, ui-serif, ui-sans-serif,
ui-monospace, ui-rounded, emoji, math, fangsong. Color keywords are
now checked case insensitively.

Ban link as well as visited (these can cause requests depending on
interaction), replace with `any-link`. Also add more CSS selectors:
default, disabled, empty, enabled, focus-visible, indeterminate,
in-range, invalid, only-child, only-of-type, optional, out-of-range,
placeholder-shown, read-only, read-write, required, root. The
combination of `:root` and color-scheme enables settings for
dark-mode. The MIME types image/avif, heic, and heif are now known
(but have no validation).

Also the `:checked` selector is now supported, so freesites can have
some CSS-based interactivity.

There’s one loss: Chrome removed Theora support in 2024 and Firefox
followed suit in version 130 (which [broke brasilian banking OTPs](https://bugzilla.mozilla.org/show_bug.cgi?id=1930548) ).
Firefox ESR (128) will still get support until may 27th 2025. Until
then we need sanitation for modern video formats like vp8 or vp9 and
webm to keep our video on demand (streaming) working.

## User experience

There are two improvements of the interface itself: Qupo1 updated the
Japanese localization and bertm polished the bookmark editor and
directory listing in the Winterfacey theme.

And thanks to Bombe the Freemail plugin now has a settings page for
the SMTP and IMAP address and port to use.

The defunct new load management statistics are now gone — thanks to
Torusrxxx. These were just distracting.

When you change the auto-updater key, updates are now checked
instantly, not only after update (for example to get updates via a
testing key for checking an update before release).

And a bug was fixed that could lead to plugins being killed early
during shutdown.

## Cleanups

Thanks to qupo1, Christophe, and Bombe, our gradle setup is more up to
date and cleaner.

And thanks to Bombe, Bertm, Venfernand, and Juiceman we purged a lot
of outdated code that is no longer needed on modern JVMs (the code
base once had to work with Java 1) or replaced it with more recent
paradigms. Those make it quite a bit more enjoyable to work with the
affected parts of the code.

Finally qupo1 fixed links and code badges in our readme and Bertm made
sure that our IPv6 preference setting keeps working in Java 24 and
later.


# Technical changelog

## Privacy and Security

- Reduce pings and DNS lookups — thanks to bertm for the careful review!
- Improve SSL: Fix SSL self-signed certificate, update cipher suite, Add HSTS header setting to upgrade FMS links to HTTPS — thanks to Torusrxxx!

## Networking layer and Optimization

- Fix bulk backoff being ignored — thanks to bertm!
- Only prefer the realtime queue to bulk with 90% probability to avoid starving bulk on very fast nodes
- Avoid unnecessary boxing and unboxing — thanks to Juiceman!
- Improve IPv6 handling, thanks to Torusrxxx!

## Expanded website support

- Add webp filter, so you can use webp on sites, thanks to Torusrxxx!
- Add many CSS options — thanks to Torusrxxx!
- recognize MIME types image/avif, heic, heif (no filters yet) — thanks to Torusrxxx!
- Do not recommend disabling js helpers in fproxy (these are reviewed, so they can be used)

## User Experience

- Update Japanese localization, thanks to qupo1!
- Fix bookmark editor indentation on Winterfacey theme and improve directory listing layout — thanks to bertm!
- Hide no longer relevant new load management statistics, thanks to Torusrxxx!
- Ensure that the updater searches for updates from an updated update key without restart.
- Add more resilient plugin list exception handling to avoid problems on shutdown

## Development support

- Update Gradle wrapper validation to latest version — thanks to qupo1!
- Update Gradle to 8.11 thanks to Christophe!
- Fix build info with gradle daemon, thanks to Bombe!
- Avoid duplicate files in jar file and and add checks — thanks to Bombe!
- Fix update.sh version in dependencies.properties (no longer pull in an older version with auto-update)

## Cleanup and refresh code

- Kill no longer useful MemoryChecker with 🔥 — thanks to Bombe and bertm for the reviews!
- Remove SHA-256 special case in PluginManager — thanks to bertm!
- Improve MultiValueTable — thanks to venfernand!
- do not check for long gone JVM bugs — thanks to bertm!
- do not intern byte arrays — thanks to bertm!
- reduce stream handling duplication in FileUtil — thanks to bertm!
- replace Logger.OSThread-based PID logging that was always disabled by NOOP — thanks to bertm!
- Remove code related to NLM load sending — thanks to bertm!
- Remove unused but dangerous Logger.fatal(...) method — thanks to bertm!
- Remove gc meddling that hasn’t been necessary for many Java releases
- replace length = 0 checks with isEmpty() — thanks to Juiceman!

## Misc

- Simplify peers parsing code
- Fix readme links and code badges — thanks to qupo1!
- Support IPV6_ADDR_PREFERENCES until Java 24 and beyond (move from reflection to unsafe) — thanks to bertm!
- Add copy of the cryptics general license (2-clause BSD)

## Plugins

- Freemail plugin: add settings page, thanks to Bombe!


1498:

# Short changelog:

This release resolves the last blocker for Freenet / Hyphanet 0.8 by
providing an official Debian package. Additionally it optimizes the
networking and data transfer core and provides many improvements for
website authors and user experience.


Starting with this release, Freenet / Hyphanet has an official Debian
package built automatically via github actions. This was the most
important [high-impact-task][] and the last release blocker of version
0.8 in our [Roadmap][]. Big thanks go to DC*!

With this finally realized, the next step is to get in contact with
the many privacy focussed distributions which build on Debian to make
`hyphanet-fred` available where it is most important. Once this is
done, tools which build on Hyphanet — like FMS, but also jSite and
tools from pyFreenet — can be packaged to work out of the box, using
Hyphanet as an ordinary background service. That’s a step towards
Hyphanet as decentralized, privacy-preserving communication backend for
other applications.

Another step towards this is accepting the Schema hypha[net] to
simplify writing browser extensions that forward hypha:-links to
Hyphanet.


The networking layer was optimized significantly. Searching packet
types is often stopped early and common or cheaper checks are done
before less common or time-consuming checks. This gives significant
reductions of CPU load, especially for very fast nodes.

Juiceman fixed a bug limiting MTU to 1280 where not needed.

And recently failed and data not found cooldown times were reduced to
5 minutes and 3 minutes, reducing one of the big annoyances when
accessing a site quickly after upload.

On the data transfer layer, healing was optimized. After 1495 strongly
increased the amount of healing to keep large files available for
longer, 1498 specializes healing to keys close to the node location.
This reduces healing per file, but improves privacy, because healing
inserts are then more similar to forwarding — they mostly send data
close to the nodes location — and it reduces the network load of
healing, because the specialized healing inserts need fewer hops to
reach the optimal storage location in the network.


In addition to these changes deep down, there are a number of directly
visible improvements.

The plugins KeepAlive and Sharesite are updated (the latter now uses
the new Night Zen Garden style). The UPnP2 plugin is now visible in
simple mode. It can replace UPnP and should work better. On the
flipside the Library plugin is moved to advanced plugins, because it
does not work reliably enough.

The plugin list is easier to navigate by removing the defunct option
to download plugins from the clearnet and by adding better styling.
Downloading from the clearnet was an unnecessary privacy risk since
we’ve been bundling essential plugins with the installer for a few
years now.


The noderef for friend-to-friend connections is shown in simple mode
again, because it is robust enough with the changes in recent years.
This should remove a barrier to adding direct connections and enabling
fully confidential messages between friends.

There are new configuration options to allow connecting via local
services. That’s a step towards making it easy to add a second layer
of security, for example confining connections to a local network.
Thanks goes to s7r for these changes!

When bandwidth detection fails, the upload bandwidth now defaults to
160KiB/s. Also the NLM config is now disabled statically. This was a
testing setup which could still be active in old nodes, but it would
break connectivity nowadays.


The default bookmarks include the Opennet SeedNodes statistics,
the generate media site to create decentralized streaming sites, and
the high-impact-tasks. The bookmarks are also re-ordered to be a
better match for newcomers. Starting category: first steps, clean
spider, Index of Indexes. For the software category ordered by ease of
use from fproxy.

For website authors, more CSS elements, selectors and combinators
(`:checked`, `word-wrap: anywhere`, `focus-within`, `^=`, `$=`, `*=`,
`>`, `+`, `~`) and additional HTML elements (`summary`, `details`,
`<meta name="Viewport"...>`) are available. This strongly expands the
possibilities of websites authors in Hyphanet, because Javascript or
webassembly are no viable options in an environment where a privacy
breach could put people at risk. We’ve seen with Java applets, that
untrusted code will always break out of its containment. The CSS
improvements in contrast provide a safe way to enable limited
interactivity.

Streaming support via m3u lists was improved to allow accessing
segments of up to 200MiB.

And using `-1` as version in a USK now properly finds version `0`, if
this is the only existing version.


There were a number of Java 21 fixes, including all our tests (thanks
to Bombe!), and improvement to the github actions (thanks to
AHOHNMYC).

In addition to that there was a lot of polish. Bert Massop and
Veniamin Fernandes replaced our homegrown CurrentTimeUTC with modern
Java options. Alex fixed the pronoun used in strings. Bombe added
getters for all direct field access in the node. Hiina reduced logging
level of store warnings so no unneeded backtraces are created for node
with large stores and Juiceman updated code to use more modern
structures.

Time-dependence of compressor selection was removed. This caused
non-determinism for inserts and could cause keys to be
non-reproducible on systems with faster or slower network.


And finally the new [exe signing workflow][] we built to fulfill the
requirements of SignPath, our new windows installer signing provider
for the upcoming releases, runs the [verify-build script][] on every
release to ensure that the jar we release has actually been built from
the sources. This provides a second safety net, in addition to
anonymous users running the script and posting the results (thanks to
all who did this — please keep it up, otherwise people have to fully
trust github). The release is not yet byte-by-byte reproducible,
because the jar MANIFEST defines among other info the exact java
version used to compile it, and the java version available differs by
distribution and time, so it would get harder over time to verify the
build.


A special thanks goes to Bombe for many careful reviews!

[high-impact-task]: https://github.com/hyphanet/wiki/wiki/High-Impact-tasks
[Roadmap]: https://github.com/hyphanet/wiki/wiki/Roadmap
[exe signing workflow]: https://github.com/hyphanet/sign-windows-installer
[verify-build script]: https://github.com/hyphanet/scripts/blob/master/verify-build

# Full changelog:


## high impact tasks
merge debian package as default build action thanks to DC*/desyncr! This resolves one of our high impact tasks.

## Plugins
Update KeepAlive to commit 86e47a101f26fd1d3be0437681a043aa4ae3f22c
Update Sharesite to 0.5.1
Move UPnP2 to normal plugins. It does not seem broken, but UPnP does
Move Library plugin to advanced plugins because new users tend to get lost with it
💄 Add better styling to the plugin list in winterfacey to make it easier to understand at a glance — thanks to Bombe
🔥 Remove option to load plugins from central server — thanks to Bombe! This was an unnecessary privacy risk, since we’re already bundling essential plugins with the installer, and it made plugin handling harder to understand.

## Bookmarks
Add high-impact-tasks to bookmarks
Add generate media site to the default bookmarks
Add Opennet SeedNodes stats site
Reorder starting bookmarks: FFS → clean spider → Index of Indexes
Reorder default software bookmarks by ease of use from fproxy
Disable activelink for Index of Indexes (workaround, because it fails)

## Optimize networking and transfer layer
break early when condition is met — thanks to Juiceman
Check the HashCode before equals. This saves ~20% method-runtime.
Re-order or’ed MessageFilters so the most likely is checked first
specialize healing to keys close to the node
fix healing decision: do not divide 0-1 by MAX_VALUE — thanks to Bombe for the review!
Reduce recently failed and data not found wait times

## Filters
CSS: Fix: checked only the first char of the key part of CSS selectors, Add test that would catch too lax filtering.
CSS: Support pseudo-element checked. This enables limited interactivity via CSS.
CSS: Support the attribute selectors ^= $= *=, Add tests.
CSS: Support Combinators > + and ~, add test for ~ and simplify the implementation
CSS: Support word-wrap: anywhere and CSS selector focus-within.
HTML: allow summary and details html element. Thanks to naejadu
HTML: accept <meta name="Viewport" ...>, thanks to torusrxxx

## Constants
Show the noderef in basic-mode: it is now robust enough

## Configuration
accepting localhost in NodeIPPortDetector and allowBindToLocalhost configurable — thanks to s7r!
Provide static methods for simpler boolean config creation
Increase default bandwidth to 160KiB upload, when detection fails
disable setting for new-load-management (NLM broke nodes)
add utility to disable a config option, thanks to Bombe

## Misc
add m3u-player insertion test: is added at end of body
[CI] Update actions, fix actions cache
♻️ add and use getters and setters for access to node fields
Increase max transparent passthrough to 200MiB links in m3u-lists.
Remove time-dependence of compressor selection. This caused
non-determinism for inserts and could cause keys to be
non-reproducible.
improve date object construction in CurrentTimeUTC.get()
Support Schema hypha[net] to simplify writing browser extensions that forward hypha://-links to Hyphanet.
polish: show datastore size warning with GiB suffix
Remove hash generation to native big integer to reduce dependencies. This had come in when merging an old pull request and added a new dependency without need.

## Polish
Replace indexOf with .contains()
Change more string comparisons into .isEmpty() checks
Capitalize L in literal longs
Add missing Global flag to GetFailed FCP message. Thanks to oishii and Bombe!
Fix links to mailing lists and IRC in CONTRIBUTING.md — thanks to Juiceman!
Deprecated custom datetime handling class CurrentTimeUTC
replace GregorianCalendar with java.time.OffsetDateTime in CurrentTimeUTC — thanks to Veniamin Fernandes
Refine ClientRequestSelectorTest, PersistentJobRunnerImplTest, SplitFileFetcherStorageTest, and SplitFileInserterStorageTest — thanks to Veniamin Fernandes
Change deprecated jcenter() maven repo to mavenCentral() — thanks to Veniamin Fernandes
Added tests for PebbleUtils — thanks to Bombe!
🐛 Fix NPE when subsets are not initialized — thanks to Bombe
reduce logging for too many excluded sub-arrays thanks to Hiina
fix the flag size of nepal — thanks to Percept0r@NYZkOs7eQ…!
Switch swiss flag to civil and state ensign — thanks to Percept0r@NY

## Fixes
Thanks to Bombe all our tests work again on Java 21!
🐛 Fix JarClassLoader’s ability to work with ServiceLoader — thanks to Bombe!
gzip: replace test workaround by fixing the output of the compressor — thanks to Bombe for the SingleOffsetReplacingOutputStream!
fix: invalid max store size showed bytes with GiB suffix


Also a special thanks to Bombe for many careful reviews!


1497:

- fixed severe path folding vulnerability.


1496:

- fix keepalive regression — thanks to PlantEater for tracking it down and fixing it!
    - fix negative maxsize per new fetch override
    - also override maxTempLength
    - LowLevelGetException also return the throwable because the error doesnt help, the real gets hidden
- update translations, thanks to the translators on transifex, especially the Russian ones!
- add meta charset tests
- replace unmaintained freemail site by maintained one — thanks to Cynthia!
- add missing test annotations — thanks to vwoodzell!


1495:

- new theme: sky-dark-static
- m3u-player: skip broken files
- m3u-player: only inline the m3u player if the page contains media tags
- new firsttime wizard (single-step joining with clearer defaults)
- Client getter honor max size argument 
- finally merged the HashingAPI by unixninja92, a GSoC project that had gotten lost in the pull requests. This provides an easy and well-tested way to create and verify different types of Hashes from byte arrays, including Sha256 and TigerTree. https://github.com/freenet/fred/pull/258
- Add web+freenet and ext+freenet as supported schemas to support extensions. Thanks to TheSeeker
- upgrade unit tests to junit4, thanks to vwoodzell!
- Eleriseth pending keys merged (performance)
- healing size increased (better lifetime for popular files)
- CSS: enable sticky — thanks to Spider Admin
- CSS: enable transition and word-wrap — thanks to naejadu, thanks to vwoodzell for the review!
- re-organize default bookmarks: first section has "starting points", thanks to vwoodzell for the review!
- old announcement fixes by toad finally merged

1494:

- Show a user alert (once every Freenet update) if the datastore is
  below 10% of available space with a link to the store size wizard page
  to make it easy to increase the store ― thanks to Trivuele
- Do not store blocks in the cache, if they are eligible for the store
  (should increase usable cache size)   thanks to Trivuele
- m3u-player: more robust sizes, do not use overlay for audio. This
  finally enables convenient Samizdat Radio
- randomize pitch black defense times and wait at least 12 hours
  between pitch black mitigations to prevent timing attacks
- bandwidth settings: parse bit suffix correctly
- improve /imagecreator/   thanks to Oleh from Redwerk
- Disable write local to datastore functionality when opennet is
  enabled; it can be useful on a small darknet, but on opennet it
  makes it easier to find downloaders.   thanks to Trivuele
- make it easier to build fred without network access   thanks to Trivuele
- fix build with modern Java: add opens jvmargs on java 17
- fix: a FOAF mitigation wasn t operational, because it lacked a
  conversion to percent.   thanks to freedom-of-depression
- remove Frost on ChatForumsToadlet from non-updated translations
  (removed 2019 from the original english)
- fix parts of the German translation


1493:

- Update the wrapper files in dependencies.properties to 3.5.30
  This change sets a consistent wrapper-version for Windows, *Nix, and
  also OSX, thanks to operhiem1 for the review
- Pseudonymous people found found a bug in our splitfile inserter that
  TheSeeker tracked down to its source. It threw an exception if the
  checksum was exactly [0,0,0,0], thanks to operhiem1 for the review
- Update default bookmarks: Replace inactive indexes, add Shoeshop,
  show active sites first. Thanks to AC4BB21B for the review!
- Fix FreenetURI intern() to not forget edition of USK — thanks to
  debbiedub!
- Replace Pointer.SIZE with Native.POINTER_SIZE for JNA 5.x — thanks
  to Leo3418 and Bombe
- fix peer scaling for very fast peers: 
  Very fast nodes have more peers to fix a conceptual mistake
  (did not take the aggregated bandwidth limit of the peers into account). 
  Thanks to TheSeeker for the review!
- more resilient noderef parsing for easier friend-to-friend connections
  try replacing spaces by newlines in noderefs when parsing fails
- Accept FreenetURI in add peer field; only try regular url on failure
  — thanks to desyncr for the review
- Add warning about surveillance through voice recognition tech
- Updater changes to add Java 17 support for existing nodes to
  wrapper.conf (required module opens for Java 17 to wrapper.conf)
  during the following update (to 1494)
- Installer changes: 
  - Java 17 support
  - increase default datastore size to 100GiB, because SSDs are much
    faster today
  - Set standard thread limit to 1000
  - decrease default thread stack size for reduced memory usage
    (512KiB)
  - Increase default bandwidth to 32KiB/s. This should improve
    performance for new nodes where UPnP does not give the speed.
    thanks to operhiem1 for the review
  - add output of filtered file to the OggFilterTest


1492:

- Fred:

- The diagnostics component provides thread runtime statistics. Thanks to DC*.
  https://github.com/freenet/fred/pull/727

- The multi-node test to show that the mitigation against the pitch
  black attack works.
  https://github.com/freenet/fred/pull/736

- The m3u-player for video on demand over Freenet now prevents most
  visual flickering.
  https://github.com/freenet/fred/pull/734

- Clickable labels for checkboxes. Thanks to AC4BB21B.
  https://github.com/freenet/fred/pull/741

- Fix: skip in support.io.SkipShieldingInputStream must return
  non-negative. Thanks to dennisnez.
  https://github.com/freenet/fred/pull/737

- Fix: NodeConfig was reading a particular config from default on every
  start up. Thanks to desyncr.
  https://github.com/freenet/fred/pull/739

- OpenJDK 16+ compat: add wrapper.conf argument to allow access to
  internal openjdk modules.
  https://github.com/freenet/fred/pull/740

- Fix: Correct "current size" option in Wizard => datastore size. Thanks to
AC4BB21B.
  https://github.com/freenet/fred/pull/742

- Plugins:

- KeyUtils:
  Fix API call to allow compiling, and
   Fix extra bytes display for composed SSK.
  both thanks to TheSeeker.
  https://github.com/freenet/plugin-KeyUtils/pull/17
  https://github.com/freenet/plugin-KeyUtils/pull/16

- Library:
  search with unchecked boxes thanks to redwerk
  https://github.com/freenet/plugin-Library/pull/14 -

  custom index docname thanks to TheSeeker
  https://github.com/freenet/plugin-Library/pull/18

- Spider:
  Fix writing to the bucket and Replace SortedIntSet thanks to redwerks
  https://github.com/freenet/plugin-Spider/pull/6

  Fix build thanks to Juiceman
  https://github.com/freenet/plugin-Spider/pull/5

- FlogHelper
  Add new media tags in tool menu: audio and video thanks to
  AlexandreRio
  https://github.com/freenet/plugin-FlogHelper/pull/19
  (you can simply set an uploaded m3u file as as source of a video
   and it will show up on your flog as video on demand)

related: nextgens fixed our website build (Thank you!), so we can release
there again. I polished our theme and the messaging on the index-page.
https://freenetproject.org/


1491:


- disable pitch black defense if swapping is disabled because the node uses opennet
  this should resolve problems to the network structure
  introduced during the pitch-black fix because
  opennet nodes started swapping when they had network-issues.
  Sorry for that :-(
- start pitch black defense 10 minutes after startup, not 1 minute after startup
  further reduce the probability of stray swapping
- fix streaming: deal with proxies providing a http node via https
  streaming should now work over proxies.
- m3u compability for mobile devices - thanks to DC*
  some mobile browsers answer the query "supports m3u" with "maybe" O_o
- add OpenBSD support thanks to jv@D7b92AQB2664AkrR
- Add compatibility method for plugins (SCGIPublisher needs it)
- recover constructor for compatibility with plugins
- Fixed several unused variables issues - thanks to Jan Gerritsen
- Fix label for logged attribute RoutingDisabled - thanks to Jan Gerritsen


1490:

- streaming in the browser,
- mitigating the pitch black attack, and
- providing the windows-installer from the node again.

- inject vanilla Javascript m3u-player into Freesites
  to get in-browser playlist streaming (with config to disable if needed).
  Thanks to nextgens and desyncr for the review!
- mitigate the pitch black attack following
  https://www.mail-archive.com/devl@freenetproject.org/msg55182.html
  Thanks to nextgens and x for the review!
- triple the UOM transfer max sizes
  so the windows-installer provided by nodes gets updated again.
  Thanks to desyncr for the review!


1489:

- Add m3u support with mp3, ogg, oga, ogv, and flac. Thanks to Bombe for reviewing!
- Reduce max backoff times from 3 hours to 8 minutes; the one safe change from the let-it-burn patchset. This might increase bandwidth available. Thanks to TheSeeker.
- add explicit license for Libre-JS to progresspage.js
- rewrite checkall, match the class for all input elements, and add explicit license for Libre-JS

1488:

- update translations to make Freenet more inviting for people around the world: update French, add Hungarian.

1487:

- The windows-installer and wintray are now being built by our continuous integration system using a more secure code-signing setup. This should reduce the scary warnings on install, reduces our dependency on specific setup of the release manager, and provides earlier checks whether something in fred broke the installer.
- Accept Android Java as not EOL to simplify the mobile-node maintenance (see https://github.com/freenet-mobile/app).
- Set context class loader when terminating plugin (by Bombe)
- Cleanups of some old deprecated code.
- Plugin-updates: KeepAlive (redwerk fixes: now ready as official plugin), plugin-FlogHelper (audio- and video-tag and more translations), Sharesite (better default CSS, clearer wording), Freemail (use newer WoT API and cleanup), KeyUtils (adjust for internal API change).
- Add UPnP2 plugin for testing.

1486:

- new jarsigner certificate should remove some ugly warnings - thanks to operhiem1 and nextgens!
- update jna to 4.5.2, because our Windows build stops working otherwise
- no longer accept certain invalid SSKs and USKs - thanks to Bombe!
- update tests from JUnit 3 to 4 - thanks to Bombe!
- increase max size for passthrough to our Windows installer should spread over UOM again
- provide more bandwidth to darknet connections than to opennet connections - thanks to Alex Williams!
- offer connection speed upgrade when increased speed is detected - thanks to Oleh from Redwerk!
- improve CSS for small devices - thanks to Oleh from Redwerk!
- improve Winterfacey style - thanks to Oleh from Redwerk!

1485:

- avoid quadratic memory allocation if PooledFileRandomAccessBuffer is swapped to disk (should fix startup loops)
- compressor: skip LZMA (old) if it is not the only requested compression method
- remove the contrib submodule from the fred repo because gradle does not need it and it hinders self-hosting
- switch to java8 as the minimum required version
- fix bug7102: don't attempt to pre-allocate when we truncate
- capture fetchKeyBoxAboveBookmarks from the theme as default value
- skip file compression where compression typically does not yield much improvement
- If there are *.fref files in a peers-offers/ folder, ask user whether to connect to them
- Theora fixes thanks to redwerk: can filter Video now!
- fix config parsing bug (thanks to redwerk)


1484:

This release fixes the last blocking problems with the new build based on gradle and JNA.
[5~
Thanks to thesnark and operhiem1 we have a fix to a way to circumvent the content filter: on
Firefox uploading a file as MIME type text/plain caused Firefox to guess the filetype and present
the user with a download-or-open dialog. This could have resulted in handing an insecure file to an
external (and potentially vulnerable) program without showing a warning to the user. Please update
ASAP to avoid that. See CVE-2019-9673 for details.

Also uploads without compression now survive restarts of the node again.

Also this release finally includes the ogg-filter from Spencer Jacksons Google Summer of Code
project. It still needs polishing and has some inefficiencies, but you can now listen to a FLAC
from Freenet directly from your browser. For example via the following key:
CHK@tOwwq70fTosZuCnpZP4j1vMkEKiFuRIblmC351CbgpE,w6BTgWSJBDOM1~lWnsE83K2gOv3huEGHzSPWFBN4xFc,AAMC--8/infinite-hands-free-software.flac

Ogg Theora is merged, too, but currently garbles most files. If you’d like to fix that, please file
a pull-request!

As a sidenote: Freenet supports listening to mp3 files in the browser since version build 1473
(2016-05-21). You can also use mp3s in a HTML5 audio-tag.

As main user-visible change: If you use the default theme, you will now see the Winterfacey
theme. If you changed it to some other theme, Freenet will continue to use that other theme.

The main networking change is to apply the less recently failed branch by toad. This should
decrease the number of recently failed errors, but it could have side-effects.

For darknet friends, the 1024 character limit of n2n messages is lifted. You can now send
messages of up to 128 kiB.

And thanks to Redwerk, there is now a "Send confidential message" button on the friends page.
Just tick the checkbox of the friends you want to contact to send n2n messages to them.

Further changes:

- update WoT plugin to build 20. Thanks to xor.
- replace handler.outputHandler.queue by handler.send - thanks to patheticcockroach
- update plugin Freemail_wot to v0.2.7.4 with better detection of contacts missing from WoT - thanks to Redwerk
- update Sharesite version to 0.4.7
- peer list: Add spacing between flag and IP address - Thanks to Bombe
- increase scaling to 3 again because 1480 nodes otherwise slow down updated nodes.
- plugin manager cleanup: more readable code
- m3u filter: can stream playlists (running in external players still needs experimentation)
- avoid losing download state on restart - thanks to ChristmasMuch from FMS
- only FMS and Sone on ChatForums suggestion page to fit the projects longstanding stance. If you disagree, you can create a freesite to promote it.
- update included seednodes


- increase scaling to 3 again because 1480 nodes otherwise slow down updated nodes
- plugin manager cleanup: more readable code 
- new ogg theora, vorbis, flac filter: can show ogg-files! - thanks to Spencer Jackson, finally merged it
- m3u filter: can stream playlists
- make winterfacey theme the default
- update WoT plugin to build 20. Thanks to xor.
- update Sharesite version to 0.4.7
- avoid losing download state on restart - thanks to ChristmasMuch from FMS
- re-apply much less recently failed - thanks to toad
- only FMS and Sone on ChatForums suggestion page to fit the projects longstanding stance. If you disagree, you can create a freesite to promote it.
- update included seednodes
- prevent content sniffing in FF. See CVE-2019-9673 for details. Thanks to thensark and operhiem1
- remove the 1024 limit for node-to-node messages
- peer list: Add spacing between flag and IP address - Thanks to Bombe
- replace handler.outputHandler.queue by handler.send - thanks to patheticcockroach
- add "send confidential message" button to friends page - thanks to Redwerk
- update plugin Freemail_wot to v0.2.7.4 with better detection of contacts missing from WoT - thanks to Redwerk
- improvement in Winterfacey

1483:

- new default theme: sky static
- included experimental winterfacey theme
- switch to gradle with witness as build system
- run in background mode
- switch from jni to jna
- Override list request identifier
- use fallocate
- ipv6 fixes
- fix warnings
- optimization
- Persist "Bookmark Updated" notifications across restarts
- minimum bandwidth increased to 10KiB again
- undo update of pinned SSL certificates (site no longer exists)
- add config option to move the fetch key box above the bookmarks

1480:

- Ship new Windows Installer and Tray
- Update Freemail to v0.2.7.3-r2

1479:

- optimized network settings for the new structure since the link
  length fix : less peers for the same bandwidth should result in
  higher throughput per connection. This allows for less powerful
  devices to join (with low bandwidth settings) and should provide
  better bandwidth utilization for very fast nodes.
- Re-enable RSA-based ciphers for SSL-connections to the node

- add jfniki index bookmark (use "add default bookmarks" to get it)
- l10n: pull translations from transifex

- plugins: WebOfTrust build0019,
  Changelog: https://github.com/freenet/plugin-WebOfTrust/releases/tag/build0019
  source available at
  CHK@gt~foMPFR5ZAhOhSOsFw68f5PBjJuCYpe~ZXPPA1t6g,pk7h34mG5hRsBPhVFWr5UllVbJXU-PS7tC9rbILvoOk,AAMC--8/WebOfTrust-build0019-source.tar.bz2
- plugins: Freemail v0.2.7.3 (new translations)
  source available at
  CHK@ZOfWMdsxhS1Lg6QKWK4CJZvVt9RYkkjFnU6-PCizHbg,zfTEQX6DexdUm9-eGyDSP5vKvp76b38SCBS7W9zkoGE,AAMC--8/Freemail-v0.2.7.3-source.tar.bz2

1478 (2017-04-05):

- prepare pinned certs for the new Amazon-web-services based site.

1477 (2017-03-09):

- fix a potential clickjacking vulnerability in legacy browsers
- patch open redirect and header injection vulnerability introduced in 1476
- SSL with RSA certificates on fproxy has been broken in 1475, fix that

1476 (2017-03-02):

- FOAF efficiency enhancements for fast nodes
- gif filter
- harden the SSL configuration of fproxy
- logger fix
- spare bitmap efficiency optimization
- reduce custom code
- show semi-persistent update info next to bookmarks
- plugin updates: Sharesite 0.4.4, Library v37, Freereader 6

1475 (2016-06-25):

- 0006745: Disk crypto: Should type password twice when setting it
- 0006344: Change default compatibility mode to COMPAT_1466
- 0006488: using “visit freesite” to visit a freesite with a hash (#) fails instead of opening it and jumping to the anchor.
- fix a critical bug: prevent announcement loops
- drop support for negtype9 (non-cummulative ack logic)
- start to warn user that java7 is EOL
- PluginInfoMessage: Fix wrong "does not provide FCP" info about plugins
- Stop warning users that java9 isn't recent enough
- Don't use FOAF if the HTL isn't high enough
- Attempt to update update.sh
- l10n improvements
- cooldown improvements
- load-limiting changes (token buckets)
- MessageFilter improvements
- relax the CSS parser (see https://github.com/freenet/fred/pull/446)
- support for HTML Audio tags
- ask/confirm the disk-crypto password in the wizard
- make the paste-a-key control usable on the WelcomeToadlet
- the default compatibility mode for inserts is now COMPAT_CURRENT
- remove the DSA related parameters from noderefs
- Fix a major bug that might explain the poor connectivity since 1473

1473 (2016-05-22):

- MP3 filter fixes
- Reduce test memory usage
- Fix opennet announcements not having location set
- Fix binary blob download over FCP
- Add The Filtered Index to the default bookmark list
- Wait for running transfers on RouteNotFound
- Mark Freenet traffic with QoS
- Fix handling of filenames with non-ASCII spaces

1472 (2016-03-19):

- Fix uploads stalling when using MAXIMUM physical security.
- Fix lots of "setNativePriority(X) has failed!", which was caused by a serious thread priority problem. This might fix nodes unexpectedly losing peers.
- Order alerts within a category by time: if you have lots of messages from darknet peers they will remain nicely sorted.
- There is now a caching layer which should significantly reduce I/O load.
- Update WebOfTrust from build 15 to build 18. Its changelogs are separate, but the changes reduce CPU load. Incremental score recomputation requires roughly 3 percent of the time of full recomputation, and queuing trust lists to disk lowers thread usage.
- Add partial Greek translation.
- Update German, Bokmål, Brazilian Portuguese, Simplified Chinese, and Traditional Chinese translations.
- Fix Bokmål localization loading.
- Remove Gantros Index from the default bookmark list because it stopped updating.
- Remove Linkageddon from the default bookmark list because it stopped updating.
- New version of UPnP to fix some instability and compatibility problems. Thanks to 007pig we have a new UPnP plugin in development which supports UPnP2, but it is not yet included.
- New version of KeyUtils.

1471 (--):

- (skipped)

1470 (2015-08-15):

- FIX Freemail problems that prevented sending mail
- remove a compromised opennet seed node
- Freemail gains a new message link on the inbox page, links to
  senders' WoT profiles, and new translations

1469 (2015-07-19):

- FIX two bugs introduced in build 1468. One caused very slow
  operation and high CPU usage with large files and physical security
  levels above None (i.e.  Freenet-level disk encryption). The other
  prevented interactive usage (e.g. freesite browsing) while finishing
  large downloads or starting large uploads.

1468 (2015-07-11):

- Replace DB4O

  - Existing unfinished downloads and uploads will be imported to a
    new format, which requires restarting them from the beginning.
  - Space for downloads is now all allocated at the start, so machines
    very low on disk space may run out, which causes downloads to
    temporarily fail until more space is available.
  - CHKs will change due to metadata bugfixes.
  - Some unofficial plugins will need to be updated because of API
    changes. Sone already works, as do all official plugins.
  - The queue format changes should make it extremely rare to lose the
    entire queue: the impact of corruption will almost always be
    localized.
  - Multi-container / site uploads can now be persistent, making it
    more practical to upload large sites.
  - Passworded physical security is now much stronger. (Full-disk
    encryption is still preferable.)


- Improve Windows installer

  - The Windows installer now defaults to starting Freenet on login.
  - There is a new Windows tray app with some useful features that is
    included with new installations.

- misc

  - The list of download keys moved from downloads/listFetchKeys.txt
    to downloads/listKeys.txt.
  - A list of upload keys is now available at uploads/listKeys.txt
  - Gantros' index is now in the default bookmarks. It uses the same
    software as Enzo's index, which is no longer updated.
  - The obsolete and deprecated XMLLibrary and XMLSpider plugins are
    no longer officially supported. They will still load for those who
    have them added, but are no longer shown on the plugin page.
  - In the interests of releasing this build more quickly, the new
    version of FlogHelper does not support exporting and importing
    backups from the web UI. The old backup code did not work with the
    new Freenet version after removing db4o. People can instead back
    up "plugins.floghelper.FlogHelper" files in the plugin-data
    directory. These can be dropped into the directory after unloading
    FlogHelper to restore a backup.
  - ThawIndexBrowser works again. Thanks saces!
  - Fred translations are updated.
  - Add two seed nodes, one sponsored by meshnet.pl - the Polish
    radio/meshnet darknet users group, and another run by
    ArneBab. Thanks!
  - Update existing seed node references.
