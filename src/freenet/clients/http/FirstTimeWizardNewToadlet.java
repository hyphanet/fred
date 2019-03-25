package freenet.clients.http;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.NodeClientCore;
import freenet.support.api.HTTPRequest;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class FirstTimeWizardNewToadlet extends WebPage {

    static final String TOADLET_URL = "/wiz/";

    private final NodeClientCore core;

    private static final String l10nPrefix = "FirstTimeWizardToadlet.";

    FirstTimeWizardNewToadlet(HighLevelSimpleClient client, NodeClientCore core) {
        super(client);
        this.core = core;
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

        showForm(ctx, formModel.toModel());
    }

    private void showForm(ToadletContext ctx, Map<String, Object> model)
            throws IOException, ToadletContextClosedException {
        PageNode page = ctx.getPageMaker().getPageNode(NodeL10n.getBase().getString(l10nPrefix + "homepageTitle"), ctx,
                new PageMaker.RenderParameters().renderNavigationLinks(false).renderStatus(false));
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

    private class FormModel {

        private String knowSomeone = "";

        private String connectToStrangers = "";

        private String haveMonthlyLimit = "";

        private String downloadLimit = "900";

        private String uploadLimit = "300";

        private String bandwidthMonthlyLimit = "0";

        private String storageLimit = "30";

        private String setPassword = "";

        private String password = "";

        private String passwordConfirmation = "";

        private Map<String, String> errors = new HashMap<>();

        FormModel() {}

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

            // TODO: validate
            errors.put("1", "1");
        }

        boolean isValid() {
            return errors.size() == 0;
        }

        Map<String, Object> toModel() {
            Map<String, Object> model = new HashMap<String, Object>() {{
                put("knowSomeone", knowSomeone.length() > 0 ? "checked" : "");
                put("connectToStrangers", connectToStrangers.length() > 0 ? "checked" : "");
                put("haveMonthlyLimit", haveMonthlyLimit.length() > 0 ? "checked" : "");
                put("downloadLimit", downloadLimit);
                put("uploadLimit", uploadLimit);
                put("bandwidthMonthlyLimit", bandwidthMonthlyLimit);
                put("storageLimit", storageLimit);
                put("setPassword", setPassword.length() > 0 ? "checked" : "");
            }};
            model.putAll(errors);
            return model;
        }

        void save() {
            // TODO
        }
    }
}
