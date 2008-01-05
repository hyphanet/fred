package freenet.store;

public interface StorableBlock {
	
	public byte[] getRoutingKey();
	
	public byte[] getFullKey();

}
