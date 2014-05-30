/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.pluginmanager;

/**
 * This container is stored in node.db4o.
 * THINK TWICE BEFORE CHANGING STUFF !
 * @author Artefact2
 */
public class PluginStoreContainer {
    public PluginStore pluginStore;
    public long nodeDBHandle;
    public String storeIdentifier;
}
