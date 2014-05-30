/*
 * This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL.
 */



package freenet.clients.http;

//~--- non-JDK imports --------------------------------------------------------

import freenet.client.HighLevelSimpleClient;

import freenet.node.NodeClientCore;

import freenet.support.HTMLNode;

//~--- JDK imports ------------------------------------------------------------

import java.io.File;

import java.util.Hashtable;

/**
 * Local file browser for the content filter toadlet.
 */
public class LocalFileFilterToadlet extends LocalFileBrowserToadlet {
    public static final String PATH = "/filter-browse/";
    public static final String POST_TO = "/filterfile/";

    public LocalFileFilterToadlet(NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient) {
        super(core, highLevelSimpleClient);
    }

    @Override
    public String path() {
        return PATH;
    }

    @Override
    protected String postTo() {
        return POST_TO;
    }

    @Override
    protected boolean allowedDir(File path) {
        return core.allowUploadFrom(path);
    }

    @Override
    protected void createSelectFileButton(HTMLNode node, String absolutePath, HTMLNode persistence) {
        node.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", selectFile,
                ContentFilterToadlet.l10n("selectFile") });
        node.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", filenameField(),
                absolutePath });
        node.addChild(persistence);
    }

    @Override
    protected void createSelectDirectoryButton(HTMLNode node, String absolutePath, HTMLNode persistence) {}

    @Override
    protected Hashtable<String, String> persistenceFields(Hashtable<String, String> set) {
        Hashtable<String, String> fieldPairs = new Hashtable<String, String>();
        String element = set.get("filter-operation");

        if (element != null) {
            fieldPairs.put("filter-operation", element);
        }

        element = set.get("result-handling");

        if (element != null) {
            fieldPairs.put("result-handling", element);
        }

        element = set.get("mime-type");

        if (element != null) {
            fieldPairs.put("mime-type", element);
        }

        return fieldPairs;
    }

    @Override
    protected String startingDir() {
        return defaultUploadDir();
    }
}
