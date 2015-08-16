package freenet.pluginmanager;

import static java.util.Collections.unmodifiableCollection;

import java.net.MalformedURLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import freenet.keys.FreenetURI;

/**
 * Container for Freenet’s official plugins.
 *
 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
 */
public class OfficialPlugins {

	private final Map<String, OfficialPluginDescription> officialPlugins = new HashMap<String, OfficialPluginDescription>();

	public OfficialPlugins() {
		try {
			addPlugin("Freemail")
					.inGroup("communication")
					.minimumVersion(15)
					.usesXml()
					.loadedFrom("CHK@6dfMgGf7YEfJhF0W~K0HUv0fnbuRwYH6iMqrLIbTI7k,huYBf8oBevwW6lRQnz-0jDP1dl5ej7FKeyVZ3CnH0Ec,AAMC--8/Freemail.jar")
					.deprecated();
			addPlugin("Freemail_wot")
					.inGroup("communication")
					.minimumVersion(26)
					.loadedFrom("CHK@~N5XxfOXgprXtRlZTIE-BcZT2UaZKFFbStEZM9LCILQ,oUOM~YlFSZgYGBZZSf898OITzOosi6UOJTxtU0Ruag0,AAMC--8/Freemail-v0.2.7.1.jar");
			addPlugin("HelloWorld")
					.inGroup("example")
					.loadedFrom("CHK@ZdTXnWV-ikkt25-y8jmhlHjCY-nikDMQwcYlWHww5eg,Usq3uRHpHuIRmMRRlNQE7BNveO1NwNI7oNKdb7cowFM,AAIC--8/HelloWorld.jar")
					.advanced();
			addPlugin("HelloFCP")
					.inGroup("example")
					.loadedFrom("CHK@0gtXJpw1QUJCmFOhoPRNqhsNbMtVw1CGVe46FUv7-e0,X8QqhtPkHoaFCUd89bgNaKxX1AV0WNBVf3sRgSF51-g,AAIC--8/HelloFCP.jar")
					.advanced();
			addPlugin("JSTUN")
					.inGroup("connectivity")
					.essential()
					.minimumVersion(2)
					.loadedFrom("CHK@Zgib8xrGxcEuix7AVB4eajton1FpNHbIJeQZgEbHMNU,BQekU261VLSDUBQPOHSMKUF5qxY1v0zjXa33RyoEbYk,AAMC--8/JSTUN.jar");
			addPlugin("KeyUtils")
					.inGroup("technical")
					.minimumVersion(5024)
					.loadedFrom("CHK@rsRNrCbYJvuCcQa3OC0c9uQh23KtqczNfZSUQfLpiAM,68yt6H-UiG00PWNQWF5uNrhuT5bcWu5CYwP~acTnzEM,AAMC--8/KeyUtils-v5024.jar")
					.advanced();
			addPlugin("MDNSDiscovery")
					.inGroup("connectivity")
					.minimumVersion(2)
					.loadedFrom("CHK@wPyhY61bsDM3OW6arFlxYX8~mBKjo~XtOTIAbT0dk88,Vr3MTAzkW5J28SJs2dTxkj6D4GVNm3u8GFsxJgzTL1M,AAIC--8/MDNSDiscovery.jar");
			addPlugin("SNMP")
					.inGroup("connectivity")
					.loadedFrom("CHK@EykJIv83UE291zONVzfXqyJYX5t66uCQJHkzQrB61MI,-npuolPZj1fcAWane2~qzRNEjKDERx52aQ5bC6NBQgw,AAIC--8/SNMP.jar")
					.advanced();
			addPlugin("TestGallery")
					.inGroup("example")
					.minimumVersion(1)
					.loadedFrom("CHK@LfJVh1EkCr4ry0yDW74vwxkX-3nkr~ztW2z0SUZHfC0,-mz7l39dC6n0RTUiSokjC~pUDO7PWZ89miYesKH0-WA,AAIC--8/TestGallery.jar")
					.experimental();
			addPlugin("ThawIndexBrowser")
					.inGroup("file-transfer")
					.minimumVersion(6)
					.usesXml()
					.loadedFrom("CHK@9bjNQtl7ndPKh~gi4woH0Xvb7uRunJ81deIlXwGE6qg,clwp0Bhx2LZxt2XCWeARqv24tBNmjlhXDZtwAJpzlIc,AAMC--8/ThawIndexBrowser-v6.jar");
			addPlugin("UPnP")
					.inGroup("connectivity")
					.essential()
					.minimumVersion(10003)
					.loadedFrom("CHK@ICSu1tgnNxJ0bApWkL-fQFswbfi9KPnmWI3Is4eq0iw,Sj1N3zdDHBbL3Uc3~eY4elqWwSP7IR1uHrKVR2-nA0s,AAMC--8/UPnP-10006.jar");
			addPlugin("XMLLibrarian")
					.inGroup("index")
					.minimumVersion(26)
					.usesXml()
					.loadedFrom("CHK@TvjyCaG1dx0xIBSJkXSKA1ZT4I~NkRKeQqwC0a0bhFM,JiQe4CRjF1RwhQRFFQzP-ih9t2i0peV0tBCfJAeFCdk,AAIC--8/XMLLibrarian.jar")
					.unsupported();
			addPlugin("XMLSpider")
					.inGroup("index")
					.minimumVersion(48)
					.usesXml()
					.loadedFrom("CHK@ne-aaLuzVZLcHj0YmrclaCXJqxsSb7q-J0eYEiL9V9o,v0EdgDGBhTE9k6GsB44UrQ4ADUq5LCUVknLaE4iSEBk,AAMC--8/XMLSpider.jar")
					.unsupported();
			addPlugin("Freereader")
					.inGroup("index")
					.minimumVersion(4)
					.usesXml()
					.loadedFrom("CHK@4PuSjXk4Z0Hdu04JLhdPHLyOVLljj8qVbjRn3rHVzvg,bDGYnuYj67Q4uzroPBEWAYWRk26bPzf-iQ4~Uo3S7mg,AAIC--8/Freereader.jar");
			addPlugin("Library")
					.inGroup("index")
					.minimumVersion(36)
					.usesXml()
					.loadedFrom("CHK@DB~oyn9M5B3Yx1B80qJbp-qp~434cxDERtgnxyhSfZ8,WRc11UVdhU4phf688oledQIV8BrNn3nvR9bVCVB-pM0,AAMC--8/Library-v36.jar");
			addPlugin("Spider")
					.inGroup("index")
					.minimumVersion(52)
					.loadedFrom("CHK@94gCPJEkEXq6Zti4wxDrqr9e~geQS4B3kdIwl4TXzV8,NUlmfjeqja28Lim6m3kTuxGHRSNtQHsbRoIAilxdkJY,AAMC--8/Spider-v52.jar")
					.advanced();
			addPlugin("WebOfTrust")
					.inGroup("communication")
					.minimumVersion(15)
					.usesXml()
					.loadedFrom("CHK@1FWUqydeyTbxC3fut51QT8VGnokQFYHMoLsqzP4RU60,BM3ibZYejeo2-sC7WklJ9I8ZJysMpO9~l9MZ8NUzP2c,AAMC--8/WebOfTrust-build0015.jar");
			addPlugin("FlogHelper")
					.inGroup("communication")
					.minimumVersion(34)
					.usesXml()
					.loadedFrom("CHK@yuiC-Dv0UYNgqRcLq4heCL-y0nHctr2LBcOsBN0ORyM,8M2bv~d7Su~O0MJHz4Tq1g3n4nrAgw-C7dn6mMFQSA4,AAMC--8/FlogHelper-v34.jar");
		} catch (MalformedURLException mue1) {
			throw new RuntimeException("Could not create FreenetURI.", mue1);
		}
	}

	private OfficialPluginBuilder addPlugin(String name) {
		return new OfficialPluginBuilder(name);
	}

	public OfficialPluginDescription get(String name) {
		return officialPlugins.get(name);
	}

	public Collection<OfficialPluginDescription> getAll() {
		return unmodifiableCollection(officialPlugins.values());
	}

	private class OfficialPluginBuilder {

		private final String name;
		private String group;
		private boolean essential;
		private long minimumVersion = -1;
		private long recommendedVersion = -1;
		private boolean usesXml;
		private FreenetURI uri;
		private boolean deprecated;
		private boolean experimental;
		private boolean advanced;
    private boolean unsupported;

		private OfficialPluginBuilder(String name) {
			this.name = name;
			addCurrentPluginDescription();
		}

		public OfficialPluginBuilder inGroup(String group) {
			this.group = group;
			addCurrentPluginDescription();
			return this;
		}

		public OfficialPluginBuilder essential() {
			essential = true;
			addCurrentPluginDescription();
			return this;
		}

		public OfficialPluginBuilder minimumVersion(int minimumVersion) {
			this.minimumVersion = minimumVersion;
			addCurrentPluginDescription();
			return this;
		}

		public OfficialPluginBuilder recommendedVersion(int recommendedVersion) {
			this.recommendedVersion = recommendedVersion;
			addCurrentPluginDescription();
			return this;
		}

		public OfficialPluginBuilder usesXml() {
			usesXml = true;
			addCurrentPluginDescription();
			return this;
		}

		public OfficialPluginBuilder loadedFrom(String uri) throws MalformedURLException {
			this.uri = new FreenetURI(uri);
			addCurrentPluginDescription();
			return this;
		}

		public OfficialPluginBuilder deprecated() {
			deprecated = true;
			addCurrentPluginDescription();
			return this;
		}

		public OfficialPluginBuilder experimental() {
			experimental = true;
			addCurrentPluginDescription();
			return this;
		}

		public OfficialPluginBuilder advanced() {
			advanced = true;
			addCurrentPluginDescription();
			return this;
		}

    public OfficialPluginBuilder unsupported() {
      unsupported = true;
      addCurrentPluginDescription();
      return this;
    }

		private void addCurrentPluginDescription() {
			officialPlugins.put(name, createOfficialPluginDescription());
		}

		private OfficialPluginDescription createOfficialPluginDescription() {
			return new OfficialPluginDescription(name, group, essential, minimumVersion, recommendedVersion, usesXml, uri, deprecated, experimental, advanced, unsupported);
		}

	}

	public static class OfficialPluginDescription {

		/** The name of the plugin */
		public final String name;

		/**
		 * The group of the plugin. The group is a technical name that needs to
		 * be translated before it is shown to the user.
		 */
		public final String group;

		/**
		 * If true, we will download it, blocking, over HTTP, during startup (unless
		 * explicitly forbidden to use HTTP). If not, we will download it on a
		 * separate thread after startup. Both are assuming we don't have it in a
		 * file.
		 */
		public final boolean essential;
		/**
		 * Minimum getRealVersion(). If the plugin is older than this, we will fail
		 * the load.
		 */
		public final long minimumVersion;
		/**
		 * Recommended getRealVersion(). If the plugin is older than this, we will
		 * download the new version in the background, and either use it on restart,
		 * or offer the user the option to reload it. This is in fact identical to
		 * what happens on a USK-based update...
		 */
		public final long recommendedVersion;
		/** Does it use XML? If so, if the JVM is vulnerable, then don't load it */
		public final boolean usesXML;
		/** FreenetURI to get the latest version from */
		public final FreenetURI uri;
		/** If true, the plugin is obsolete. */
		public final boolean deprecated;
		/** If true, the plugin is experimental. */
		public final boolean experimental;
		/**
		 * If true, the plugin is geeky - it should not be shown except in advanced
		 * mode even though it's not deprecated nor is it experimental.
		 */
		public final boolean advanced;
    /**
     * If true, the plugin used to be official, but is no longer supported.
     * These are not shown even in advanced mode.
     */
    public final boolean unsupported;

		OfficialPluginDescription(String name, String group, boolean essential, long minVer, long recVer, boolean usesXML, FreenetURI uri, boolean deprecated, boolean experimental, boolean advanced, boolean unsupported) {
			this.name = name;
			this.group = group;
			this.essential = essential;
			this.minimumVersion = minVer;
			this.recommendedVersion = recVer;
			this.usesXML = usesXML;
			this.uri = uri;
			this.deprecated = deprecated;
			this.experimental = experimental;
			this.advanced = advanced;
      this.unsupported = unsupported;
		}

		public String getLocalisedPluginName() {
			return PluginManager.getOfficialPluginLocalisedName(name);
		}

		public String getLocalisedPluginDescription() {
			return PluginManager.l10n("pluginDesc." + name);
		}

	}

}
