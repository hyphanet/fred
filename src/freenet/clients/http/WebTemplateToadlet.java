package freenet.clients.http;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simplest template engine.
 * <p>
 * Expression syntax:
 * <ul>
 * <li>${...} - Variable expressions.
 * <li>#{...} - Message (l10n) expressions.
 * <li>${...Error} - Placeholder for errors. Template try to get errors from model.errors map.
 * Unused error placeholders will be removed from the page.
 * </ul>
 */
abstract class WebTemplateToadlet extends Toadlet {

    private static final String ROOT_PATH = "templates/";

    WebTemplateToadlet(HighLevelSimpleClient client) {
        super(client);
    }

    /**
     * Add html from template to {@code parent} node.
     *
     * @param parent Parent html node.
     * @param templateName Html page name (without extension) that located in src/freenet/clients/http/templates.
     * @param model The map with all variables that template should use.
     * @throws IOException If the template cannot be read.
     */
    void addChild(HTMLNode parent, String templateName, Map<String, Object> model) throws IOException {
        addChild(parent, templateName, model, "");
    }

    void addChild(HTMLNode parent, String templateName, Map<String, Object> model, String l10nPrefix) throws IOException {
        try (InputStream stream =
                     getClass().getResourceAsStream(ROOT_PATH + templateName + ".html")) {
            ByteArrayOutputStream content = new ByteArrayOutputStream();

            int len;
            byte[] contentBytes = new byte[1024];
            while ((len = stream.read(contentBytes)) != -1) {
                content.write(contentBytes, 0, len);
            }

            String template = content.toString(StandardCharsets.UTF_8.name());

            // replace variables in html
            for (Map.Entry<String, Object> entry : model.entrySet()) {
                template = template.replaceAll("\\$\\{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }

            // replace l10n in html
            String key;
            while ((key = getL10nKey(template)) != null) {
                template = template.replaceAll("#\\{" + key + "}", NodeL10n.getBase().getString(l10nPrefix + key));
            }

            // replace form errors in html
            Object errorsObject = model.get("errors");
            if (errorsObject instanceof Map) {
                Map errors = (Map) errorsObject;
                for (Object errKey : errors.keySet()) {
                    template = template.replaceAll("\\$\\{" + errKey + "}", errors.get(errKey).toString());
                }
            }
            template = template.replaceAll("\\$\\{.*Error}", ""); // remove unused error placeholders

            parent.addChild("%", template);
        }
    }

    private String getL10nKey(String template) {
        Pattern pattern = Pattern.compile("#\\{.*}");
        Matcher matcher = pattern.matcher(template);
        try {
            if (matcher.find()) {
                String key = matcher.group();
                return key.substring(2, key.length() - 1);
            }
        } catch (IllegalStateException ignored) {}
        return null;
    }
}
