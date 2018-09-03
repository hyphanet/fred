package freenet.node;

/** A listener interface that can be used to be notified about peer status change events*/
public interface PeerStatusChangeListener{
    /** Peers status have changed*/
    void onPeerStatusChange();
}
