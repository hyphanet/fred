/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.Enumeration;

import freenet.client.HighLevelSimpleClient;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.l10n.L10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.Base64;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;

/**
 * A first time wizard aimed to ease the configuration of the node.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 */
public class FirstTimeWizardToadlet extends Toadlet {
	private final Node node;
	private final NodeClientCore core;
	private final Config config;
	
	
	FirstTimeWizardToadlet(HighLevelSimpleClient client, Node node) {
		super(client);
		this.node = node;
		this.core = node.clientCore;
		this.config = node.config;
	}
	
	public static final String TOADLET_URL = "/wizard/";
	
	public void handleGet(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		int currentStep = request.getIntParam("step");
		
		if(currentStep == 1) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step1Title"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode languageInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode languageInfoboxHeader = languageInfobox.addChild("div", "class", "infobox-header");
			HTMLNode languageInfoboxContent = languageInfobox.addChild("div", "class", "infobox-content");
			
			languageInfoboxHeader.addChild("#", l10n("selectLanguage"));
			languageInfoboxContent.addChild("#", l10n("selectLanguageLong"));
			HTMLNode languageForm = ctx.addFormChild(languageInfoboxContent, ".", "languageForm");
			HTMLNode result = languageForm.addChild("select", "name", "language");
			
			result.addChild("option", new String[] { "value", "selected" }, new String[] { "en", "selected" }, "English");
			result.addChild("option", "value", "fr", "Français");
			result.addChild("option", "value", "pl", "Polski");
			result.addChild("option", "value", "it", "Italiano");
			result.addChild("option", "value", "se", "Svenska");
			result.addChild("option", "value", "no", "Norsk");
			// We don't propose unknown languages here
			
			languageForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "languageF", L10n.getString("Toadlet.clickHere")});
			languageForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			return;
		} else if(currentStep == 2) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step2Title"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode bandwidthInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode bandwidthnfoboxHeader = bandwidthInfobox.addChild("div", "class", "infobox-header");
			HTMLNode bandwidthInfoboxContent = bandwidthInfobox.addChild("div", "class", "infobox-content");
			
			bandwidthnfoboxHeader.addChild("#", l10n("bandwidthLimit"));
			bandwidthInfoboxContent.addChild("#", l10n("bandwidthLimitLong"));
			HTMLNode bandwidthForm = ctx.addFormChild(bandwidthInfoboxContent, ".", "bwForm");
			HTMLNode result = bandwidthForm.addChild("select", "name", "bw");
			
			result.addChild("option", new String[] { "value", "selected" }, new String[] { "15", "selected" }, "I don't know");
			result.addChild("option", "value", "8K", "lower speed");
			result.addChild("option", "value", "12K", "1024+/128 kbps");
			result.addChild("option", "value", "24K", "1024+/256 kbps");
			result.addChild("option", "value", "48K", "1024+/512 kbps");
			result.addChild("option", "value", "96K", "1024+/1024 kbps");
			result.addChild("option", "value", "1000K", "higher speed");
			
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "bwF", L10n.getString("Toadlet.clickHere")});
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			return;
		} else if(currentStep == 3) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step3Title"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode bandwidthInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode bandwidthnfoboxHeader = bandwidthInfobox.addChild("div", "class", "infobox-header");
			HTMLNode bandwidthInfoboxContent = bandwidthInfobox.addChild("div", "class", "infobox-content");
			
			bandwidthnfoboxHeader.addChild("#", l10n("datastoreSize"));
			bandwidthInfoboxContent.addChild("#", l10n("datastoreSizeLong"));
			HTMLNode bandwidthForm = ctx.addFormChild(bandwidthInfoboxContent, ".", "dsForm");
			HTMLNode result = bandwidthForm.addChild("select", "name", "ds");
			
			result.addChild("option", new String[] { "value", "selected" }, new String[] { "1G", "selected" }, "1GiB");
			result.addChild("option", "value", "2G", "2GiB");
			result.addChild("option", "value", "3G", "3GiB");
			result.addChild("option", "value", "5G", "5GiB");
			result.addChild("option", "value", "10G", "10GiB");
			result.addChild("option", "value", "20G", "20GiB");
			result.addChild("option", "value", "30G", "30GiB");
			result.addChild("option", "value", "50G", "50GiB");
			result.addChild("option", "value", "100G", "100GiB");
			
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "dsF", L10n.getString("Toadlet.clickHere")});
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			return;
		} else if(currentStep == 4) {
			HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("step4Title"), false, ctx);
			HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
			
			HTMLNode bandwidthInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode bandwidthnfoboxHeader = bandwidthInfobox.addChild("div", "class", "infobox-header");
			HTMLNode bandwidthInfoboxContent = bandwidthInfobox.addChild("div", "class", "infobox-content");

			Enumeration interfaces = NetworkInterface.getNetworkInterfaces();
			HTMLNode bandwidthForm = ctx.addFormChild(bandwidthInfoboxContent, ".", "networkForm");
			
			short ifCount = 0;
			HTMLNode ifList = new HTMLNode("div", "class", "interface");
			while(interfaces.hasMoreElements()) {
				NetworkInterface currentInterface = (NetworkInterface) interfaces.nextElement();
				if(currentInterface == null) continue;
				
				Enumeration ipAddresses = currentInterface.getInetAddresses();
				while(ipAddresses.hasMoreElements()) {
					InetAddress ip = (InetAddress) ipAddresses.nextElement();
					if((ip == null) || (ip.isLoopbackAddress())) continue;
					ifCount++;
					HTMLNode ipDiv = ifList.addChild("div", "class", "ipAddress");
					ipDiv.addChild("#", L10n.getString("FirstTimeWizardToadlet.iDoTrust", new String[] { "interface", "ip" }, new String[] { currentInterface.getName(), ip.getHostAddress() }));
					ipDiv.addChild("input", new String[] { "type", "name", "value" }, new String[] { "radio", Base64.encode(ip.getAddress()), "true" }, L10n.getString("Toadlet.yes"));
					ipDiv.addChild("input", new String[] { "type", "name", "value", "checked" }, new String[] { "radio", Base64.encode(ip.getAddress()), "false", "checked" }, L10n.getString("Toadlet.no"));
				}
			}
			
			if(ifCount > 0) {
				bandwidthnfoboxHeader.addChild("#", l10n("isNetworkTrusted"));
				bandwidthInfoboxContent.addChild("#", l10n("isNetworkTrustedLong"));
				bandwidthForm.addChild(ifList);
			} else {
				bandwidthnfoboxHeader.addChild("#", l10n("noNetworkIF"));
				bandwidthInfoboxContent.addChild("#", l10n("noNetworkIFLong"));				
			}
			
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "networkF", L10n.getString("Toadlet.clickHere")});
			bandwidthForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", L10n.getString("Toadlet.cancel")});
			this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
			return;
		}
		
		HTMLNode pageNode = ctx.getPageMaker().getPageNode(l10n("homepageTitle"), false, ctx);
		HTMLNode contentNode = ctx.getPageMaker().getContentNode(pageNode);
		
		HTMLNode welcomeInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		HTMLNode welcomeInfoboxHeader = welcomeInfobox.addChild("div", "class", "infobox-header");
		HTMLNode welcomeInfoboxContent = welcomeInfobox.addChild("div", "class", "infobox-content");
		welcomeInfoboxHeader.addChild("#", l10n("welcomeInfoboxTitle"));
		
		HTMLNode firstParagraph = welcomeInfoboxContent.addChild("p");
		firstParagraph.addChild("#", l10n("welcomeInfoboxContent1") + ' ');
		firstParagraph.addChild("a", "href", "?step=1").addChild("#", L10n.getString("Toadlet.clickHere"));
		
		HTMLNode secondParagraph = welcomeInfoboxContent.addChild("p");
		secondParagraph.addChild("a", "href", "/").addChild("#", l10n("skipWizard"));
		
		this.writeReply(ctx, 200, "text/html; charset=utf-8", "OK", pageNode.generate());
	}
	
	public void handlePost(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, "Unauthorized", L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		String passwd = request.getPartAsString("formPassword", 32);
		boolean noPassword = (passwd == null) || !passwd.equals(core.formPassword);
		if(noPassword) {
			if(Logger.shouldLog(Logger.MINOR, this)) Logger.minor(this, "No password ("+passwd+" should be "+core.formPassword+ ')');
			super.writeTemporaryRedirect(ctx, "invalid/unhandled data", "/");
			return;
		}
		
		if(request.isPartSet("languageF")) {
			String selectedLanguage = request.getPartAsString("language", 4);
			
			try {
				config.get("node").set("l10n", selectedLanguage);
				Logger.normal(this, "The language has been set to "+ selectedLanguage);
			} catch (InvalidConfigValueException e) {
				Logger.error(this, "Should not happen, please report!" + e);
			}
			super.writeTemporaryRedirect(ctx, "step2", TOADLET_URL+"?step=2");
			return;
		} else if(request.isPartSet("bwF")) {
			String selectedUploadSpeed =request.getPartAsString("bw", 6);
			
			try {
				config.get("node").set("outputBandwidthLimit", selectedUploadSpeed);
				Logger.normal(this, "The outputBandwidthLimit has been set to "+ selectedUploadSpeed);
			} catch (InvalidConfigValueException e) {
				Logger.error(this, "Should not happen, please report!" + e);
			}
			super.writeTemporaryRedirect(ctx, "step3", TOADLET_URL+"?step=3");
			return;
		} else if(request.isPartSet("dsF")) {
			String selectedStoreSize =request.getPartAsString("ds", 6);
			
			try {
				config.get("node").set("storeSize", selectedStoreSize);
				Logger.normal(this, "The storeSize has been set to "+ selectedStoreSize);
			} catch (InvalidConfigValueException e) {
				Logger.error(this, "Should not happen, please report!" + e);
			}
			super.writeTemporaryRedirect(ctx, "step3", TOADLET_URL+"?step=4");
			return;
		} else if(request.isPartSet("networkF")) {
			StringBuffer sb = new StringBuffer();
			// prevent the user from locking himself out
			sb.append("127.0.0.1,0:0:0:0:0:0:0:1");
			short ifCount = 0;
			
			Enumeration interfaces = NetworkInterface.getNetworkInterfaces();
			while(interfaces.hasMoreElements()) {
				NetworkInterface currentIF = (NetworkInterface) interfaces.nextElement();
				if(currentIF == null) continue;
				
				Enumeration ipAddresses = currentIF.getInetAddresses();
				while(ipAddresses.hasMoreElements()) {
					InetAddress currentInetAddress = (InetAddress) ipAddresses.nextElement();
					if((currentInetAddress == null) || (currentInetAddress.isLoopbackAddress())) continue;
					
					String isIFSelected =request.getPartAsString(Base64.encode(currentInetAddress.getAddress()), 255);
					if((isIFSelected != null) && (isIFSelected.equals("true"))) {
						sb.append(',');
						sb.append(currentInetAddress.getHostAddress());
						ifCount++;
					}
				}
			}
			
			if(ifCount > 0) {
				try {
					// Java doesn't provide a way to get the netmask : workaround and bind only to trusted if
					config.get("fcp").set("bindTo", sb.toString());
					config.get("fcp").set("allowedHosts", "*");
					config.get("fcp").set("allowedHostsFullAccess", "*");

					config.get("fproxy").set("bindTo", sb.toString());
					config.get("fproxy").set("allowedHosts", "*");
					config.get("fproxy").set("allowedHostsFullAccess", "*");

					Logger.normal(this, "Network allowance list has been set to "+ sb.toString());
				} catch (InvalidConfigValueException e) {
					Logger.error(this, "Should not happen, please report!" + e);
				}
			}

			super.writeTemporaryRedirect(ctx, "step4", TOADLET_URL+"?step=5");
			return;
		}
		
		super.writeTemporaryRedirect(ctx, "invalid/unhandled data", TOADLET_URL);
	}
	
	private String l10n(String key) {
		return L10n.getString("FirstTimeWizardToadlet."+key);
	}

	public String supportedMethods() {
		return "GET, POST";
	}
}
