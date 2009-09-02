package freenet.pluginmanager;

public class PluginTooOldException extends PluginNotFoundException {

	final boolean downloaded;
	
	public PluginTooOldException(String string, boolean downloaded) {
		super(string);
		this.downloaded = downloaded;
	}

}
