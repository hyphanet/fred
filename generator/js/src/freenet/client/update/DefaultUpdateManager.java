package freenet.client.update;

import java.util.HashMap;
import java.util.Map;

import freenet.client.FreenetJs;
import freenet.client.elemetupdaters.IElementUpdater;
import freenet.client.elemetupdaters.ReplacerUpdater;
import freenet.client.tools.Base64;

public class DefaultUpdateManager implements IUpdateManager {

	private static Map<String, IElementUpdater>	updaters	= new HashMap<String, IElementUpdater>();
	static {
		updaters.put("replacerUpdater", new ReplacerUpdater());
	}

	public static final String					SEPARATOR	= ":";

	@Override
	public void updated(String message) {
		FreenetJs.log("message:"+message);
		FreenetJs.log("updatertypeencoded:"+message.substring(0, message.indexOf(SEPARATOR)));
		FreenetJs.log("updatertypedecoded:"+Base64.decode(message.substring(0, message.indexOf(SEPARATOR))));
		
		String updaterType = Base64.decode(message.substring(0, message.indexOf(SEPARATOR)));
		String elementId = Base64.decode(message.substring(message.indexOf(SEPARATOR) + SEPARATOR.length(), message.lastIndexOf(SEPARATOR)));
		String content = Base64.decode(message.substring(message.lastIndexOf(SEPARATOR) + SEPARATOR.length()));
		FreenetJs.log("elementiddecoded:"+elementId);
		FreenetJs.log("contentdecoded:"+content);
		IElementUpdater updater = updaters.get(updaterType);
		if (updater == null) {
			FreenetJs.log("Updater cannot be found:" + updaterType + "! maybe you mistyped the type, or forgot to add to the map.");
		} else {
			updater.update(elementId, content);
		}

	}

}
