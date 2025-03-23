package freenet.node.useralerts;

import java.lang.ref.WeakReference;

import freenet.node.PeerNode;
import freenet.support.HTMLNode;

public interface NodeToNodeMessageUserAlert {
    WeakReference<PeerNode> getPeerRef(); 
    String getSourceNodeName();
}
