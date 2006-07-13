class Packet
{
	// Packet types
	public final static int PING = 1, PONG = 2;
	
	public int source, destination; // Network addresses
	public int type; // Ping, pong, etc
	public int seq; // Sequence number (actually just a nonce at the moment)
	public double latency; // Stored here for convenience
	
	public Packet (int type, int seq)
	{
		// Addresses and latency will be filled in later
		this.type = type;
		this.seq = seq;
	}
}
