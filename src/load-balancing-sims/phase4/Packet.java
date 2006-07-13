class Packet
{
	// Packet types
	public final static int DATA = 1, ACK = 2;
	public final static int HEADER_SIZE = 50;
	
	public int src, dest; // Network addresses
	public int type; // Data, ack, etc
	public int size; // Packet size in bytes, including headers
	public int seq; // Sequence number or explicit ack
	
	public double latency; // Link latency (stored here for convenience)
	public double sent; // Time at which the packet was last transmitted
	
	public Packet (int type, int dataSize)
	{
		this.type = type;
		size = dataSize + HEADER_SIZE;
	}
}
