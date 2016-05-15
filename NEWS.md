next ():

- Add a NEWS file
- Sort alerts within a category by time, newest first (including node-to-node messages).
- Optimize CSS to load everything but the first theme in parallel
- Tighten security (Remove DSA and compat code).
- Only update essential plugins if we have to.
- Improve translations.
- Show peer locations, not distance in peer location histogram.
- Add much more CSS support to the content filter.
- Fix problems with blank bookmark names. Thanks to _xiaoyu for the report!
- Fix missing the software category in bookmarks.
- Fix caching of static assets

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
