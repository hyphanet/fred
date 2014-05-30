/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.pluginmanager;

//~--- non-JDK imports --------------------------------------------------------

import freenet.client.HighLevelSimpleClient;

import freenet.keys.FreenetURI;

import freenet.node.Node;

import freenet.pluginmanager.OfficialPlugins.OfficialPluginDescription;

public class PluginDownLoaderOfficialFreenet extends PluginDownLoaderFreenet {
    public PluginDownLoaderOfficialFreenet(HighLevelSimpleClient client, Node node, boolean desperate) {
        super(client, node, desperate);
    }

    @Override
    public FreenetURI checkSource(String source) throws PluginNotFoundException {
        OfficialPluginDescription desc = node.getPluginManager().getOfficialPlugin(source);

        if (desc == null) {
            throw new PluginNotFoundException("Not in the official plugins list: " + source);
        }

        if (desc.uri != null) {
            return desc.uri;
        } else {
            return node.nodeUpdater.getURI().setDocName(source).setSuggestedEdition(desc.minimumVersion).sskForUSK();
        }
    }

    @Override
    String getPluginName(String source) throws PluginNotFoundException {
        return source + ".jar";
    }

    public boolean isOfficialPluginLoader() {
        return true;
    }
}
