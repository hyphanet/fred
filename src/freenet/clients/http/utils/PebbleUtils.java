package freenet.clients.http.utils;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;

import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.loader.ClasspathLoader;
import com.mitchellbosecke.pebble.loader.Loader;
import com.mitchellbosecke.pebble.template.PebbleTemplate;

import freenet.support.HTMLNode;

public class PebbleUtils {
  private static final String TEMPLATE_ROOT_PATH = "freenet/clients/http/templates/";
  private static final String TEMPLATE_NAME_SUFFIX = ".html";
  private static final PebbleEngine templateEngine;

  static {
    Loader loader = new ClasspathLoader(PebbleUtils.class.getClassLoader());
    loader.setPrefix(PebbleUtils.TEMPLATE_ROOT_PATH);
    loader.setSuffix(PebbleUtils.TEMPLATE_NAME_SUFFIX);

    templateEngine = new PebbleEngine.Builder().loader(loader).extension(new L10nExtension()).build();
  }

  public static void addChild(
      HTMLNode parent,
      String templateName,
      Map<String, Object> model,
      String l10nPrefix) throws
      IOException {
    model.put("l10nPrefix", l10nPrefix);
    PebbleTemplate template = templateEngine.getTemplate(templateName);

    Writer writer = new StringWriter();
    template.evaluate(writer, model);

    parent.addChild("%", writer.toString());
  }
}
