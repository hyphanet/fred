import java.util.LinkedList;
import java.util.NoSuchElementException;

class NetworkInterface implements EventTarget
{
	public int address; // Represents an IP address and port
	private Node owner; // The owner of this network interface
	private double txSpeed, rxSpeed; // Bytes per second
	
	private LinkedList<Packet> txQueue; // Queue of outgoing packets
	private LinkedList<Packet> rxQueue; // Queue of incoming packets
	private int txQueueSize, rxQueueSize; // Limited-size drop-tail queues
	private int txQueueMaxSize, rxQueueMaxSize; // Bytes
	
	public NetworkInterface (Node owner, double txSpeed, double rxSpeed)
	{
		this.owner = owner;
		this.txSpeed = txSpeed;
		this.rxSpeed = rxSpeed;
		txQueue = new LinkedList<Packet>();
		rxQueue = new LinkedList<Packet>();
		txQueueSize = rxQueueSize = 0; // Bytes
		txQueueMaxSize = 10000;
		rxQueueMaxSize = 20000;
		// Attach the interface to the network
		address = Network.register (this);
	}
		
	// Called by Peer
	public void send (Packet p, int dest, double latency)
	{
		p.src = address;
		p.dest = dest;
		p.latency = latency;
		if (txQueueSize + p.size > txQueueMaxSize) {
			Event.log (address + " no room in txQueue");
			return; // Packet lost
		}
		txQueue.add (p);
		txQueueSize += p.size;
		Event.log (address + " " + txQueueSize + " bytes in txQueue");
		// If there are no other packets in the queue, start to transmit
		if (txQueue.size() == 1) txStart (p);
	}
	
	// Event callbacks
	
	// Add a packet to the rx queue
	private void rxQueueAdd (Packet p)
	{
		if (rxQueueSize + p.size > rxQueueMaxSize) {
			Event.log (address + " no room in rxQueue");
			return; // Packet lost
		}
		rxQueue.add (p);
		rxQueueSize += p.size;
		Event.log (address + " " + rxQueueSize + " bytes in rxQueue");
		// If there are no other packets in the queue, start to receive
		if (rxQueue.size() == 1) rxStart (p);
	}
	
	// Start receiving a packet
	private void rxStart (Packet p)
	{
		// Delay depends on rx speed
		Event.schedule (this, p.size / rxSpeed, RX_END, p);
	}
	
	// Finish receiving a packet, pass it to the node
	private void rxEnd (Packet p)
	{
		owner.handlePacket (p);
		// If there's another packet waiting, start to receive it
		try {
			rxQueueSize -= p.size;
			rxQueue.remove (p);
			rxStart (rxQueue.getFirst());
		}
		catch (NoSuchElementException nse) {}
	}
	
	// Start transmitting a packet
	private void txStart (Packet p)
	{
		// Delay depends on tx speed
		Event.schedule (this, p.size / txSpeed, TX_END, p);
	}
	
	// Finish transmitting a packet
	private void txEnd (Packet p)
	{
		Network.deliver (p);
		// If there's another packet waiting, start to transmit it
		try {
			txQueueSize -= p.size;
			txQueue.remove (p);
			txStart (txQueue.getFirst());
		}
		catch (NoSuchElementException nse) {}
	}
	
	// EventTarget interface
	public void handleEvent (int type, Object data)
	{
		switch (type) {
			case RX_Q_ADD:
			rxQueueAdd ((Packet) data);
			break;
			
			case RX_START:
			rxStart ((Packet) data);
			break;
			
			case RX_END:
			rxEnd ((Packet) data);
			break;
			
			case TX_START:
			txStart ((Packet) data);
			break;
			
			case TX_END:
			txEnd ((Packet) data);
			break;
		}
	}
	
	// Each EventTarget class has its own event codes
	public final static int RX_Q_ADD = 1;
	public final static int RX_START = 2;
	public final static int RX_END = 3;
	public final static int TX_START = 4;
	public final static int TX_END = 5;
}
