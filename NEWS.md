next ():

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
