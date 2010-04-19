package freenet.pluginmanager;

public class PluginTooOldException extends PluginNotFoundException {

	final private static long serialVersionUID = -3104024342634046289L;

	public PluginTooOldException(String string) {
		super(string);
	}

}
