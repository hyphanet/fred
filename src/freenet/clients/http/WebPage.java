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

abstract class WebPage extends Toadlet {

    private static final String ROOT_PATH = "templates/";

    WebPage(HighLevelSimpleClient client) {
        super(client);
    }

    void addChild(HTMLNode parent, String templateName, Map<String, Object> model) throws IOException {
        addChild(parent, templateName, model, "");
    }

    void addChild(HTMLNode parent, String templateName, Map<String, Object> model, String l10nPrefix) throws IOException {
        try (InputStream stream =
                     getClass().getResourceAsStream(ROOT_PATH + templateName + ".html")) {
            ByteArrayOutputStream content = new ByteArrayOutputStream();

            int len;
            byte[] contentBytes = new byte[1024];
            while ((len = stream.read(contentBytes)) != -1)
                content.write(contentBytes, 0, len);

            String template = content.toString(StandardCharsets.UTF_8.name());

            for (Map.Entry<String, Object> entry : model.entrySet())
                template = template.replaceAll("\\$\\{" + entry.getKey() + "}", String.valueOf(entry.getValue()));

            String key;
            while ((key = getL10nKey(template)) != null)
                template = template.replaceAll("#\\{" + key + "}", NodeL10n.getBase().getString(l10nPrefix + key));

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
