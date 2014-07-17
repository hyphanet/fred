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
					.minimumVersion(24)
					.loadedFrom("CHK@DcKbZCZ0wXfRUUfTXRGIhlqm7dhQ-ZM3VJ1KRFWEfuc,mWbiDOpbujik5VuPoHbyu2aza~fzkdpiDV6ThJLcS8E,AAMC--8/Freemail.jar");
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
					.minimumVersion(5021)
					.loadedFrom("CHK@~hS3~oxnvcUZNKW~zdmhh0BJRvQ1NYg9qSiVJt0IPSU,fRRHqSbuZ7dqPo0rYPBuP5NbZPGXtGe-Ug7iGjd~4SY,AAMC--8/KeyExplorer.jar")
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
					.minimumVersion(5)
					.usesXml()
					.loadedFrom("CHK@G8Je6u7aY3PN7KsxNYlQJzkYJure-5YNiZ~kFhwjHgs,ci3UDwFeWDzZzBvNsga1aM2vjouOUMMyKO8HAeOgFgs,AAIC--8/ThawIndexBrowser.jar");
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
					.deprecated();
			addPlugin("XMLSpider")
					.inGroup("index")
					.minimumVersion(48)
					.usesXml()
					.loadedFrom("CHK@ne-aaLuzVZLcHj0YmrclaCXJqxsSb7q-J0eYEiL9V9o,v0EdgDGBhTE9k6GsB44UrQ4ADUq5LCUVknLaE4iSEBk,AAMC--8/XMLSpider.jar")
					.deprecated();
			addPlugin("Freereader")
					.inGroup("index")
					.minimumVersion(4)
					.usesXml()
					.loadedFrom("CHK@4PuSjXk4Z0Hdu04JLhdPHLyOVLljj8qVbjRn3rHVzvg,bDGYnuYj67Q4uzroPBEWAYWRk26bPzf-iQ4~Uo3S7mg,AAIC--8/Freereader.jar");
			addPlugin("Library")
					.inGroup("index")
					.minimumVersion(35)
					.usesXml()
					.loadedFrom("CHK@VhhWe6sT41pPei4SBwxcmRXrJpMfPDXTFhtJ4rFxfsk,MrPki7hU35x2MHvV~8am~CdF-B4xzqxjMwDtqFVYJLQ,AAMC--8/Library.jar");
			addPlugin("Spider")
					.inGroup("index")
					.minimumVersion(51)
					.loadedFrom("CHK@CcJfB~uOTgbzdpVr8htrhLXs0uNsVW6KFRpEvHGjXDU,BPr2fm9Cq9gj7BQeJdLbkCmcmXRx-e-b6aerDzSK4zk,AAMC--8/Spider.jar")
					.advanced();
			addPlugin("WebOfTrust")
					.inGroup("communication")
					.minimumVersion(13)
					.usesXml()
					.loadedFrom("CHK@dSfeVmjFX15QVyFCTUQmZItrJi8XnoYpiapxLTxaQeg,wizfFOtkKSBEdjUYgjCUJczjl74r0CjRBfzvaRvKUMo,AAMC--8/WebOfTrust.jar");
			addPlugin("FlogHelper")
					.inGroup("communication")
					.minimumVersion(31)
					.usesXml()
					.loadedFrom("CHK@UAgvzuTihdGnVmtQ7R2PMoSzzcPpMisS8AILj7j78Ek,mRnnc-NDu~ktr4809nfD2huLk-6thoiMK9Khndo3Toc,AAMC--8/FlogHelper.jar");
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

		private void addCurrentPluginDescription() {
			officialPlugins.put(name, createOfficialPluginDescription());
		}

		private OfficialPluginDescription createOfficialPluginDescription() {
			return new OfficialPluginDescription(name, group, essential, minimumVersion, recommendedVersion, usesXml, uri, deprecated, experimental, advanced);
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

		OfficialPluginDescription(String name, String group, boolean essential, long minVer, long recVer, boolean usesXML, FreenetURI uri, boolean deprecated, boolean experimental, boolean advanced) {
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
		}

		public String getLocalisedPluginName() {
			return PluginManager.getOfficialPluginLocalisedName(name);
		}

		public String getLocalisedPluginDescription() {
			return PluginManager.l10n("pluginDesc." + name);
		}

	}

}
