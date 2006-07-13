import java.util.HashMap;
import java.util.ArrayList;

class Node implements EventTarget
{
	private NetworkInterface net;
	private HashMap<Integer,Peer> peers; // Look up a peer by its address
	public int sent, received;
	
	public Node()
	{
		peers = new HashMap<Integer,Peer>();
		net = new NetworkInterface (this);
		sent = received = 0;
	}
	
	public void connect (Node n, double latency)
	{
		Peer p = new Peer (n.net.address, latency, net);
		peers.put (n.net.address, p);
	}
	
	// Called by NetworkInterface
	public void handlePacket (Packet p)
	{
		if (p.type == Packet.PING) {
			Event.log (this + " received ping " + p.seq);
			Peer source = peers.get (p.source);
			source.sendPacket (new Packet (Packet.PONG, p.seq));
			Event.log (this + " sent pong " + p.seq);
		}
		else if (p.type == Packet.PONG) {
			Event.log (this + " received pong " + p.seq);
			received++;
		}
	}
	
	// Event callback
	private void generatePing()
	{
		// Choose a random peer
		int randomIndex = (int) (Math.random() * peers.size());
		ArrayList<Peer> a = new ArrayList<Peer> (peers.values());
		Peer randomPeer = a.get (randomIndex);
		
		// Each ping/pong pair is identified by a random nonce
		int seq = (int) (Math.random() * 1000000);
		randomPeer.sendPacket (new Packet (Packet.PING, seq));
		Event.log (this + " sent ping " + seq);
		sent++;
		// Each node sends 10 pings
		if (sent == 10) return;
		
		// Schedule another ping after a random delay
		Event.schedule (this, Math.random(), GENERATE_PING, null);
	}
	
	// EventTarget interface
	public void handleEvent (int type, Object data)
	{
		if (type == GENERATE_PING) generatePing();
	}
	
	// Each EventTarget class has its own event codes
	public final static int GENERATE_PING = 1;	
}
