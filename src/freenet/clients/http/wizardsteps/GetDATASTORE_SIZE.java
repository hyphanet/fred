package freenet.clients.http.wizardsteps;

import freenet.clients.http.ToadletContext;
import freenet.config.Config;
import freenet.config.Option;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.HTMLNode;
import freenet.support.SizeUtil;
import freenet.support.api.HTTPRequest;
import freenet.support.io.FileUtil;

/**
 * Allows the user to select datastore size, considering available storage space when offering options.
 */
public class GetDATASTORE_SIZE extends AbstractGetStep {

	public static final String TITLE_KEY = "step4Title";

	private final NodeClientCore core;
	private final Config config;

	public GetDATASTORE_SIZE(NodeClientCore core, Config config) {
		this.config = config;
		this.core = core;
	}

	@Override
	public String getPage(HTMLNode contentNode, HTTPRequest request, ToadletContext ctx) {
		HTMLNode bandwidthInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode bandwidthnfoboxHeader = bandwidthInfobox.addChild("div", "class", "infobox-header");
		HTMLNode bandwidthInfoboxContent = bandwidthInfobox.addChild("div", "class", "infobox-content");

		bandwidthnfoboxHeader.addChild("#", l10n("datastoreSize"));
		bandwidthInfoboxContent.addChild("#", l10n("datastoreSizeLong"));
		HTMLNode bandwidthForm = ctx.addFormChild(bandwidthInfoboxContent, ".", "dsForm");
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
			        new String[] { SizeUtil.formatSize(current), "on" }, l10n("currentPrefix")+" "+SizeUtil.formatSize(current));
		} else if(autodetectedSize != -1) {
			result.addChild("option", new String[] { "value", "selected" }, new String[] { SizeUtil.formatSize(autodetectedSize), "on" }, SizeUtil.formatSize(autodetectedSize));
		}
		if(autodetectedSize != 512*1024*1024) {
			result.addChild("option", "value", "512M", "512 MiB");
		}
		// We always allow at least 1GB
		result.addChild("option", "value", "1G", "1 GiB");
		if(maxSize >= 2*1024*1024*1024) {
			if(autodetectedSize != -1 || !sizeOption.isDefault()) {
				result.addChild("option", "value", "2G", "2 GiB");
			} else {
				result.addChild("option", new String[] { "value", "selected" }, new String[] { "2G", "on" }, "2GiB");
			}
		}
		if(maxSize >= 3*1024*1024*1024) result.addChild("option", "value", "3G", "3 GiB");
		if(maxSize >= 5*1024*1024*1024) result.addChild("option", "value", "5G", "5 GiB");
		if(maxSize >= 10*1024*1024*1024) result.addChild("option", "value", "10G", "10 GiB");
		if(maxSize >= 20*1024*1024*1024) result.addChild("option", "value", "20G", "20 GiB");
		if(maxSize >= 30*1024*1024*1024) result.addChild("option", "value", "30G", "30 GiB");
		if(maxSize >= 50*1024*1024*1024) result.addChild("option", "value", "50G", "50 GiB");
		if(maxSize >= 100*1024*1024*1024) result.addChild("option", "value", "100G", "100 GiB");

		bandwidthForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "dsF", l10n("continue")});
		bandwidthForm.addChild("input",
		        new String[] { "type", "name", "value" },
		        new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel")});

		return contentNode.generate();
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
			long shortSize = -1;
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
			}else // <5GB => 256MB
				shortSize = 256*1024*1024;

			return shortSize;
		}
	}
}
