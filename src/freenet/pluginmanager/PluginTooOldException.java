package freenet.pluginmanager;

public class PluginTooOldException extends PluginNotFoundException {

  private static final long serialVersionUID = -3104024342634046289L;

  public PluginTooOldException(String string) {
    super(string);
  }
}
