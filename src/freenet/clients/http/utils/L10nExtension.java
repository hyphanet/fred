package freenet.clients.http.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mitchellbosecke.pebble.extension.AbstractExtension;
import com.mitchellbosecke.pebble.extension.Function;
import com.mitchellbosecke.pebble.template.EvaluationContext;
import com.mitchellbosecke.pebble.template.PebbleTemplate;

import freenet.l10n.NodeL10n;

class L10nExtension extends AbstractExtension {

  @Override
  public Map<String, Function> getFunctions() {
    Map<String, Function> functions = new HashMap<>();
    functions.put("l10n", new L10nFunction());
    return functions;
  }

    static class L10nFunction implements Function {

        @Override
        public Object execute(Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) {
            Object key = args.get("0");
            if (key == null) {
                return "null";
            }
            return NodeL10n.getBase().getString(context.getVariable("l10nPrefix") + key.toString());
        }

        @Override
        public List<String> getArgumentNames() {
            return null;
        }
    }
}
