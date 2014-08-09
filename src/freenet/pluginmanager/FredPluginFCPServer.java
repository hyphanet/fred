/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.node.fcp.FCPPluginClient;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * Plugins which provide FCP services to clients must implement this interface.
 * 
 * @see FCPPluginClient A client will be represented as this class to the server plugin. Its JavaDoc also contains a nice overview of the code path which 
 *                      FCP plugin messages flow through.
 * @author xor (xor@freenetproject.org)
 */
public interface FredPluginFCPServer {
    
    public static enum ClientPermissions {
        ACCESS_DIRECT,
        ACCESS_FCP_RESTRICTED,
        ACCESS_FCP_FULL
    };
    

    void handleFCPPluginClientMessage(FCPPluginClient client, SimpleFieldSet parameters, Bucket data, ClientPermissions permissions);

}
