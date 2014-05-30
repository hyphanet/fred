/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.pluginmanager;

public class PluginTooOldException extends PluginNotFoundException {
    final private static long serialVersionUID = -3104024342634046289L;

    public PluginTooOldException(String string) {
        super(string);
    }
}
