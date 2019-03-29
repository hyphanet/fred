package freenet.clients.http;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.wizardsteps.BandwidthLimit;
import freenet.clients.http.wizardsteps.DATASTORE_SIZE;
import freenet.config.Config;
import freenet.config.ConfigException;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;
import freenet.support.io.DatastoreUtil;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class FirstTimeWizardNewToadlet extends WebPage {

    static final String TOADLET_URL = "/wiz/";

    private final NodeClientCore core;

    private final Config config;

    private static final String l10nPrefix = "FirstTimeWizardToadlet.";

    FirstTimeWizardNewToadlet(HighLevelSimpleClient client, NodeClientCore core, Config config) {
        super(client);
        this.core = core;
        this.config = config;
    }

    @Override
    public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx)
            throws ToadletContextClosedException, IOException {
        if(!ctx.checkFullAccess(this)) return;

        showForm(ctx, new FormModel().toModel());
    }

    public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx)
            throws ToadletContextClosedException, IOException {
        if(!ctx.checkFullAccess(this)) return;

        FormModel formModel = new FormModel(request);

        if (formModel.isValid()) {
            formModel.save();
            super.writeTemporaryRedirect(ctx, "Wizard complete", WelcomeToadlet.PATH);
        }

        // form model not valid
        showForm(ctx, formModel.toModel());
    }

    private void showForm(ToadletContext ctx, Map<String, Object> model)
            throws IOException, ToadletContextClosedException {
        PageNode page = ctx.getPageMaker().getPageNode(l10n("homepageTitle"), ctx,
                new PageMaker.RenderParameters().renderNavigationLinks(false).renderStatus(false));
        page.addCustomStyleSheet("/static/first-time-wizard.css");
        addChild(page.content, "first-time-wizard", model, l10nPrefix);
        this.writeHTMLReply(ctx, 200, "OK", page.outer.generate());
    }

    @Override
    public boolean allowPOSTWithoutPassword() {
        return true;
    }

    @Override
    public String path() {
        return TOADLET_URL;
    }

    private static String l10n(String key) {
        return NodeL10n.getBase().getString(l10nPrefix + key);
    }

    private static String l10n(String key, String value) {
        return NodeL10n.getBase().getString(l10nPrefix + key, value);
    }

    private class FormModel {

        private String knowSomeone = "";

        private String connectToStrangers = "";

        private String haveMonthlyLimit = "";

        private String downloadLimit = "900";

        private String uploadLimit = "300";

        private String bandwidthMonthlyLimit = "50";

        private String storageLimit = "1";

        private String setPassword = "";

        private String password = "";

        private String passwordConfirmation = "";

        private Map<String, String> errors = new HashMap<>();

        FormModel() {
            long autodetectedDatastoreSize = DatastoreUtil.autodetectDatastoreSize(core, config);
            if (autodetectedDatastoreSize > 0)
                storageLimit = String.format("%.2f", (float) autodetectedDatastoreSize / DatastoreUtil.oneGiB);
        }

        FormModel(HTTPRequest request) {
            knowSomeone = request.getPartAsStringFailsafe("knowSomeone", 20);
            connectToStrangers = request.getPartAsStringFailsafe("connectToStrangers", 20);
            haveMonthlyLimit = request.getPartAsStringFailsafe("haveMonthlyLimit", 20);
            downloadLimit = request.getPartAsStringFailsafe("downLimit", 100);
            uploadLimit = request.getPartAsStringFailsafe("upLimit", 100);
            bandwidthMonthlyLimit = request.getPartAsStringFailsafe("monthlyLimit", 100);
            storageLimit = request.getPartAsStringFailsafe("storage", 100);
            setPassword = request.getPartAsStringFailsafe("setPassword", 20);
            password = request.getPartAsStringFailsafe("password", 100);
            passwordConfirmation = request.getPartAsStringFailsafe("confirmPassword", 100);

            // validate
            if (haveMonthlyLimit.isEmpty()) {
                try {
                    long downloadLimit = Fields.parseLong(this.downloadLimit + "KiB");
                    if (downloadLimit < Node.getMinimumBandwidth())
                        errors.put("downloadLimitError",
                                FirstTimeWizardNewToadlet.l10n("valid.downloadLimit", Integer.toString(Node.getMinimumBandwidth() / 1024)));
                } catch (NumberFormatException e) {
                    errors.put("downloadLimitError",
                            FirstTimeWizardNewToadlet.l10n("valid.number.prefix.downloadLimit") + " " + e.getMessage());
                }

                try {
                    long uploadLimit = Fields.parseLong(this.uploadLimit + "KiB");
                    if (uploadLimit < Node.getMinimumBandwidth())
                        errors.put("uploadLimitError",
                                FirstTimeWizardNewToadlet.l10n("valid.uploadLimit", Integer.toString(Node.getMinimumBandwidth() / 1024)));
                } catch (NumberFormatException e) {
                    errors.put("uploadLimitError",
                            FirstTimeWizardNewToadlet.l10n("valid.number.prefix.uploadLimit") + " " + e.getMessage());
                }
            }

            if (!haveMonthlyLimit.isEmpty()) {
                try {
                    double monthlyLimit = Double.parseDouble(bandwidthMonthlyLimit);
                    if (monthlyLimit < BandwidthLimit.minMonthlyLimit)
                        errors.put("bandwidthMonthlyLimitError",
                                FirstTimeWizardNewToadlet.l10n("valid.bandwidthMonthlyLimit", Double.toString(BandwidthLimit.minMonthlyLimit)));
                } catch (NumberFormatException e) {
                    errors.put("bandwidthMonthlyLimitError",
                            FirstTimeWizardNewToadlet.l10n("valid.number.prefix.bandwidthMonthlyLimit") + " " + e.getMessage());
                }
            }

            try {
                long maxDatastoreSize;
                long storageLimit = Fields.parseLong(this.storageLimit + "GiB");
                if (storageLimit < Node.MIN_STORE_SIZE)
                    errors.put("storageLimitError", NodeL10n.getBase().getString("Node.invalidMinStoreSize"));
                else if (storageLimit > (maxDatastoreSize = DatastoreUtil.maxDatastoreSize()))
                    errors.put("storageLimitError",
                            NodeL10n.getBase().getString("Node.invalidMaxStoreSize",
                                    String.format("%.2f", (float) maxDatastoreSize / DatastoreUtil.oneGiB)));
            } catch (NumberFormatException e) {
                errors.put("storageLimitError",
                        FirstTimeWizardNewToadlet.l10n("valid.number.prefix.storageLimit") + " " + e.getMessage());
            }

            if (!setPassword.isEmpty()) {
                if (password.isEmpty())
                    errors.put("passwordError", NodeL10n.getBase().getString("SecurityLevels.passwordNotZeroLength"));
                if (!password.equals(passwordConfirmation))
                    errors.put("passwordError", NodeL10n.getBase().getString("SecurityLevels.passwordsDoNotMatch"));
            }
        }

        boolean isValid() {
            return errors.isEmpty();
        }

        Map<String, Object> toModel() {
            return new HashMap<String, Object>() {{
                put("knowSomeone", knowSomeone.length() > 0 ? "checked" : "");
                put("connectToStrangers", connectToStrangers.length() > 0 ? "checked" : "");
                put("haveMonthlyLimit", haveMonthlyLimit.length() > 0 ? "checked" : "");
                put("downloadLimit", downloadLimit);
                put("uploadLimit", uploadLimit);
                put("bandwidthMonthlyLimit", bandwidthMonthlyLimit);
                put("storageLimit", storageLimit);
                put("setPassword", setPassword.length() > 0 ? "checked" : "");

                put("errors", errors);
            }};
        }

        // TODO
        void save() {
            if (knowSomeone.isEmpty()) {
                // Opennet
            } else {
                if (connectToStrangers.isEmpty()) {
                    // Darknet
                }
                else {
                    // Opennet + Darknet
                }
            }

            try {
                if (haveMonthlyLimit.isEmpty()) { // save download & uploadLimit
                    config.get("node").set("inputBandwidthLimit", downloadLimit + "KiB");
                    config.get("node").set("outputBandwidthLimit", uploadLimit + "KiB");
                } else { // save bandwidthMonthlyLimit
                    BandwidthLimit bandwidth = new BandwidthLimit(Fields.parseLong(bandwidthMonthlyLimit + "GiB"));
                    config.get("node").set("inputBandwidthLimit", Long.toString(bandwidth.downBytes));
                    config.get("node").set("outputBandwidthLimit", Long.toString(bandwidth.upBytes));
                }
            } catch (ConfigException e) {
                Logger.error(this, "Should not happen, please report! " + e, e);
            }

//            DATASTORE_SIZE.setDatastoreSize(storageLimit + "GiB", config, this);

            if (setPassword.isEmpty()) {
                // set password requirements settings
            } else {
                // save password
                // set password requirements settings
            }
        }
    }
}
