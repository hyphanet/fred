package freenet.node.useralerts;

import freenet.clients.fcp.FCPMessage;
import freenet.clients.fcp.FeedMessage;
import freenet.clients.http.wizardsteps.DATASTORE_SIZE;
import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.node.Version;
import freenet.support.HTMLNode;

/**
 * Inform the user that their datastore is way below recommended datastore size.
 * Include link to go to datastore size step in first time wizard to reconfigure.
 *
 * Use currently configured datastore size, not the current size on disk.
 * There may already be a resize datastore operation queued or in progress,
 * and this alert should be based on the final size when it is done.
 *
 * In the text, recommend 20%, but only show warning below 10%, to encourage the
 * user to configure with a big margin so alert is not triggered too soon again.
 *
 * If the user dismisses the warning, wait with showing it again until Freenet
 * has been upgraded to a new version, to avoid showing it too often.
 */
public class DatastoreTooSmallAlert implements UserAlert {
	private final NodeClientCore core;

	private static String l10n(String key) {
		return NodeL10n.getBase().getString("DataStoreTooSmallAlert."+key);
	}

	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("DataStoreTooSmallAlert."+key, pattern, value);
	}

	public DatastoreTooSmallAlert(NodeClientCore core) {
		this.core = core;
	}

	@Override
	public boolean userCanDismiss() {
		return true;
	}

	@Override
	public String getTitle() {
		return l10n("title");
	}

	@Override
	public String getShortText() {
		return getTitle();
	}

	@Override
	public String getText() {
		Config config = core.getNode().getConfig();

		// Datastore size as configured in wizard is the sum of these three.
		long storeSize = config.get("node").getLong("storeSize");
		long clientCacheSize = config.get("node").getLong("clientCacheSize");
		long slashdotCacheSize = config.get("node").getLong("slashdotCacheSize");
		long totalSize = storeSize + clientCacheSize + slashdotCacheSize;
		// And this corrective factor, since size on disk is up towards 3.6% larger.
		totalSize = (long)((double)totalSize * 1.036);
		// And round it, in case manually configured and not exact multiple of GiB.
		long currentSize = (totalSize + 512*1024*1024) / (1024*1024*1024);
		// Calculate available size the same way as in wizard, recommend at least 20% of that.
		long availableSize = DATASTORE_SIZE.maxDatastoreSize(core.getNode()) / (1024*1024*1024);
		long minSize = availableSize / 5;
		// Wizard never recommends sizes above 100 GiB, so claim a minimum of at most 50 GiB.
		if (minSize > 50) minSize = 50;

		StringBuffer sb = new StringBuffer();
		sb.append(l10n("description", "size", Long.toString(minSize)));
		sb.append(" ");
		sb.append(l10n("current", "size", currentSize + " GiB"));
		sb.append(l10n("available", "size", availableSize + " GiB"));
		return sb.toString();
	}

	@Override
	public HTMLNode getHTMLText() {
		Config config = core.getNode().getConfig();

		// Datastore size as configured in wizard is the sum of these three.
		long storeSize = config.get("node").getLong("storeSize");
		long clientCacheSize = config.get("node").getLong("clientCacheSize");
		long slashdotCacheSize = config.get("node").getLong("slashdotCacheSize");
		long totalSize = storeSize + clientCacheSize + slashdotCacheSize;
		// And this corrective factor, since size on disk is up towards 3.6% larger.
		totalSize = (long)((double)totalSize * 1.036);
		// And round it, in case manually configured and not exact multiple of GiB.
		long currentSize = (totalSize + 512*1024*1024) / (1024*1024*1024);
		// Calculate available size the same way as in wizard, recommend at least 20% of that.
		long availableSize = DATASTORE_SIZE.maxDatastoreSize(core.getNode()) / (1024*1024*1024);
		long minSize = availableSize / 5;
		// Wizard never recommends sizes above 100 GiB, so claim a minimum of at most 50 GiB.
		if (minSize > 50) minSize = 50;

		HTMLNode alertNode = new HTMLNode("div");
		alertNode.addChild("p", l10n("description", "size", Long.toString(minSize)));
		HTMLNode sizesNode = new HTMLNode("p");
		sizesNode.addChild("#", l10n("current", "size", currentSize + " GiB"));
		sizesNode.addChild("br");
		sizesNode.addChild("#", l10n("available", "size", availableSize + " GiB"));
		alertNode.addChild(sizesNode);
		alertNode.addChild("a", "href", "/wizard/?step=DATASTORE_SIZE&singlestep=true")
			.addChild("#", l10n("submit"));

		return alertNode;
	}

	@Override
	public short getPriorityClass() {
		return UserAlert.WARNING;
	}

	@Override
	public boolean isValid() {
		Config config = core.getNode().getConfig();

		// Datastore size as configured in wizard is the sum of these three.
		long storeSize = config.get("node").getLong("storeSize");
		long clientCacheSize = config.get("node").getLong("clientCacheSize");
		long slashdotCacheSize = config.get("node").getLong("slashdotCacheSize");
		long totalSize = storeSize + clientCacheSize + slashdotCacheSize;
		// And this corrective factor, since size on disk is up towards 3.6% larger.
		totalSize = (long)((double)totalSize * 1.036);
		// And round it, in case manually configured and not exact multiple of GiB.
		long currentSize = (totalSize + 512*1024*1024) / (1024*1024*1024);
		// Calculate available size the same way as in wizard, only warn if below 10% of that.
		long availableSize = DATASTORE_SIZE.maxDatastoreSize(core.getNode()) / (1024*1024*1024);
		long minSize = availableSize / 10;
		// Wizard never recommends sizes above 100 GiB, so never warn if above 25 GiB.
		if (minSize > 25) minSize = 25;

		// Check if warning has already been dismissed on this Freenet version
		int currentVersion = Version.buildNumber();
		int dismissedVersion = core.getNode().getConfig().get("node").getInt("datastoreTooSmallDismissed");

		return currentSize < minSize && currentVersion != dismissedVersion;
	}

	@Override
	public void isValid(boolean validity) {
	}

	@Override
	public String dismissButtonText() {
		return NodeL10n.getBase().getString("UserAlert.hide");
	}

	@Override
	public boolean shouldUnregisterOnDismiss() {
		return true;
	}

	@Override
	public void onDismiss() {
		String currentVersion = Integer.toString(Version.buildNumber());

		try {
			core.getNode().getConfig().get("node").set("datastoreTooSmallDismissed", currentVersion);
		} catch (ConfigException e) {
		}
	}

	@Override
	public String anchor() {
		return "datastore-too-small";
	}

	@Override
	public boolean isEventNotification() {
		return false;
	}

	@Override
	public long getUpdatedTime() {
		return System.currentTimeMillis();
	}

	@Override
	public FCPMessage getFCPMessage() {
		return new FeedMessage(getTitle(), getShortText(), getText(), getPriorityClass(), getUpdatedTime());
	}
}
