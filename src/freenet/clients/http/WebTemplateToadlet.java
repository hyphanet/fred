package freenet.clients.http;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.utils.PebbleUtils;
import freenet.support.HTMLNode;
import java.io.*;
import java.util.Map;

abstract class WebTemplateToadlet extends Toadlet {

  WebTemplateToadlet(HighLevelSimpleClient client) {
    super(client);
  }

  /**
   * Add html from template to {@code parent} node.
   *
   * @param parent Parent html node.
   * @param templateName Html page name (without extension) that located in
   *     src/freenet/clients/http/templates.
   * @param model The map with all variables that template should use.
   * @throws IOException If the template cannot be read.
   */
  void addChild(HTMLNode parent, String templateName, Map<String, Object> model)
      throws IOException {
    PebbleUtils.addChild(parent, templateName, model, "");
  }

  void addChild(HTMLNode parent, String templateName, Map<String, Object> model, String l10nPrefix)
      throws IOException {
    PebbleUtils.addChild(parent, templateName, model, l10nPrefix);
  }
}
