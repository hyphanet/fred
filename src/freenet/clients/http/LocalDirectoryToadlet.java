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

public abstract class LocalDirectoryToadlet extends LocalFileBrowserToadlet {
    protected static final String basePath = "/directory-browser";
    protected final String postTo;

    public LocalDirectoryToadlet(NodeClientCore core, HighLevelSimpleClient highLevelSimpleClient, String postTo) {
        super(core, highLevelSimpleClient);
        this.postTo = postTo;
    }

    @Override
    public String path() {
        return basePath + postTo;
    }

    public static String basePath() {
        return basePath;
    }

    @Override
    protected String postTo() {
        return postTo;
    }

    @Override
    protected void createSelectFileButton(HTMLNode fileRow, String filename, HTMLNode persist) {}
}
