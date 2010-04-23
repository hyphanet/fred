package freenet.io.comm;

/** Thrown if a peer is restarted during an attempt to send a throttled packet, wait
 * for an incoming packet from a peer, etc. */ 
public class PeerRestartedException extends DisconnectedException {

   final private static long serialVersionUID = 616182042289792833L;

}
