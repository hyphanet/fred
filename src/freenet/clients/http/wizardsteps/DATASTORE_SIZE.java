package freenet.clients.http.wizardsteps;

import freenet.clients.http.FirstTimeWizardToadlet;
import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.config.Option;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.Fields;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.SizeUtil;
import freenet.support.api.HTTPRequest;
import freenet.support.io.FileUtil;

/**
 * Allows the user to select datastore size, considering available storage space when offering options.
 */
public class DATASTORE_SIZE implements Step {

	private final NodeClientCore core;
	private final Config config;

	public DATASTORE_SIZE(NodeClientCore core, Config config) {
		this.config = config;
		this.core = core;
	}

	@Override
	public void getStep(HTTPRequest request, PageHelper helper) {
		HTMLNode contentNode = helper.getPageContent(WizardL10n.l10n("step4Title"));
		HTMLNode bandwidthInfoboxContent = helper.getInfobox("infobox-header", WizardL10n.l10n("datastoreSize"),
		        contentNode, null, false);

		bandwidthInfoboxContent.addChild("#", WizardL10n.l10n("datastoreSizeLong"));
		HTMLNode bandwidthForm = helper.addFormChild(bandwidthInfoboxContent, ".", "dsForm");
		HTMLNode result = bandwidthForm.addChild("select", "name", "ds");

		long maxSize = maxDatastoreSize();

		long autodetectedSize = canAutoconfigureDatastoreSize();
		if(maxSize < autodetectedSize) autodetectedSize = maxSize;

		@SuppressWarnings("unchecked")
		Option<Long> sizeOption = (Option<Long>) config.get("node").getOption("storeSize");
		if(!sizeOption.isDefault()) {
			long current = sizeOption.getValue();
			result.addChild("option",
			        new String[] { "value", "selected" },
			        new String[] { SizeUtil.formatSize(current), "on" }, WizardL10n.l10n("currentPrefix")+" "+SizeUtil.formatSize(current));
		} else if(autodetectedSize != -1) {
			result.addChild("option",
			        new String[] { "value", "selected" },
			        new String[] { SizeUtil.formatSize(autodetectedSize), "on" }, SizeUtil.formatSize(autodetectedSize));
		}
		if(autodetectedSize != 512*1024*1024) {
			result.addChild("option", "value", "512M", "512 MiB");
		}
		// We always allow at least 1GB
		result.addChild("option", "value", "1G", "1 GiB");
		if(maxSize >= 2l*1024*1024*1024) {
			if(autodetectedSize != -1 || !sizeOption.isDefault()) {
				result.addChild("option", "value", "2G", "2 GiB");
			} else {
				result.addChild("option",
				        new String[] { "value", "selected" },
				        new String[] { "2G", "on" }, "2GiB");
			}
		}
		if(maxSize >= 3l*1024*1024*1024) result.addChild("option", "value", "3G", "3 GiB");
		if(maxSize >= 5l*1024*1024*1024) result.addChild("option", "value", "5G", "5 GiB");
		if(maxSize >= 10l*1024*1024*1024) result.addChild("option", "value", "10G", "10 GiB");
		if(maxSize >= 20l*1024*1024*1024) result.addChild("option", "value", "20G", "20 GiB");
		if(maxSize >= 30l*1024*1024*1024) result.addChild("option", "value", "30G", "30 GiB");
		if(maxSize >= 50l*1024*1024*1024) result.addChild("option", "value", "50G", "50 GiB");
		if(maxSize >= 100l*1024*1024*1024) result.addChild("option", "value", "100G", "100 GiB");


		//Put buttons below dropdown.
		HTMLNode below = bandwidthForm.addChild("div");
		below.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "back", NodeL10n.getBase().getString("Toadlet.back")});
		below.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "next", NodeL10n.getBase().getString("Toadlet.next")});
	}

	@Override
	public String postStep(HTTPRequest request) {
		// drop down options may be 6 chars or less, but formatted ones e.g. old value if re-running can be more
		_setDatastoreSize(request.getPartAsStringFailsafe("ds", 20));
		return FirstTimeWizardToadlet.WIZARD_STEP.BANDWIDTH.name();
	}

	private void _setDatastoreSize(String selectedStoreSize) {
		try {
			long size = Fields.parseLong(selectedStoreSize);
			// client cache: 10% up to 200MB
			long clientCacheSize = Math.min(size / 10, 200*1024*1024);
			// recent requests cache / slashdot cache / ULPR cache
			int upstreamLimit = config.get("node").getInt("outputBandwidthLimit");
			int downstreamLimit = config.get("node").getInt("inputBandwidthLimit");
			// is used for remote stuff, so go by the minimum of the two
			int limit;
			if(downstreamLimit <= 0) limit = upstreamLimit;
			else limit = Math.min(downstreamLimit, upstreamLimit);
			// 35KB/sec limit has been seen to have 0.5 store writes per second.
			// So saying we want to have space to cache everything is only doubling that ...
			// OTOH most stuff is at low enough HTL to go to the datastore and thus not to
			// the slashdot cache, so we could probably cut this significantly...
			long lifetime = config.get("node").getLong("slashdotCacheLifetime");
			long maxSlashdotCacheSize = (lifetime / 1000) * limit;
			long slashdotCacheSize = Math.min(size / 10, maxSlashdotCacheSize);

			long storeSize = size - (clientCacheSize + slashdotCacheSize);

			System.out.println("Setting datastore size to "+Fields.longToString(storeSize, true));
			config.get("node").set("storeSize", Fields.longToString(storeSize, true));
			if(config.get("node").getString("storeType").equals("ram"))
				config.get("node").set("storeType", "salt-hash");
			System.out.println("Setting client cache size to "+Fields.longToString(clientCacheSize, true));
			config.get("node").set("clientCacheSize", Fields.longToString(clientCacheSize, true));
			if(config.get("node").getString("clientCacheType").equals("ram"))
				config.get("node").set("clientCacheType", "salt-hash");
			System.out.println("Setting slashdot/ULPR/recent requests cache size to "+Fields.longToString(slashdotCacheSize, true));
			config.get("node").set("slashdotCacheSize", Fields.longToString(slashdotCacheSize, true));


			Logger.normal(this, "The storeSize has been set to " + selectedStoreSize);
		} catch(ConfigException e) {
			Logger.error(this, "Should not happen, please report!" + e, e);
		}
	}

	private long maxDatastoreSize() {
		long maxMemory = Runtime.getRuntime().maxMemory();
		if(maxMemory == Long.MAX_VALUE) return Long.MAX_VALUE;
		if(maxMemory < 128*1024*1024) return 1024*1024*1024;
		return (((((maxMemory - 100*1024*1024)*4)/5) / (4 * 3) /* it's actually size per one key of each type */)) * Node.sizePerKey;
	}

	private long canAutoconfigureDatastoreSize() {
		if(!config.get("node").getOption("storeSize").isDefault())
			return -1;

		long freeSpace = FileUtil.getFreeSpace(core.node.getStoreDir());

		if(freeSpace <= 0) {
			return -1;
		} else {
			long shortSize;
			if(freeSpace / 20 > 1024 * 1024 * 1024) { // 20GB+ => 5%, limit 256GB
				// If 20GB+ free, 5% of available disk space.
				// Maximum of 256GB. That's a 128MB bloom filter.
				shortSize = Math.min(freeSpace / 20, 256*1024*1024*1024L);
			}else if(freeSpace / 10 > 1024 * 1024 * 1024) { // 10GB+ => 10%
				// If 10GB+ free, 10% of available disk space.
				shortSize = freeSpace / 10;
			}else if(freeSpace / 5 > 1024 * 1024 * 1024) { // 5GB+ => 512MB
				// If 5GB+ free, default to 512MB
				shortSize = 512*1024*1024;
			}else { // <5GB => 256MB
				shortSize = 256*1024*1024;
			}

			return shortSize;
		}
	}
}
