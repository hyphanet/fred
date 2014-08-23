/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import freenet.node.fcp.FCPPluginClient;

/**
 * FCP server or client plugins which transfer FCP messages to each other using a {@link FCPPluginClient} must implement this interface to provide a function
 * which handles the received messages.
 * 
 * For symmetry, the child interfaces {@link ClientSideMessageHandler} and {@link ServerSideMessageHandler} do not provide any different functions.
 * They exist nevertheless to allow JavaDoc to explain differences in what the server and client are allowed to do.
 * You <b>must</b> follow the restrictions which are explained there.
 * 
 * FIXME: This shall replace the interfaces {@link FredPluginFCPServer} and {@link FredPluginFCPClient}. Instead of having two different message handling
 * function signatures for client and server as it is in the existing interfaces, this should have one message handling function which is the same.
 * This will keep the {@link FCPPluginClient} send() functions simple. So please design a common message handling function and then delete the old interfaces.
 * 
 * @author xor (xor@freenetproject.org)
 * @see PluginRespirator#connecToOtherPlugin(String, FredPluginFCPClient) PluginRespirator provides the function to obtain FCP connections to a server plugin.
 * @see FCPPluginClient FCPPluginClient provides an overview of the internal code paths through which plugin FCP messages flow.
 */
public interface FredPluginFCPMessageHandler { 
   
    /**
     * FIXME: Migrate JavaDoc of {@link FredPluginFCPServer} to this, then delete that interface.
     */
    public interface ServerSideFCPMessageHandler extends FredPluginFCPMessageHandler {
        
    }
       
    /**
     * FIXME: Migrate JavaDoc of {@link FredPluginFCPServer} to this, then delete that interface.
     */
    public interface ClientSideFCPMessageHandler extends FredPluginFCPMessageHandler {
        
    }

}
