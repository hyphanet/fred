package freenet.node.fcp;

import java.io.IOException;
import java.io.OutputStream;

import freenet.node.Node;
import freenet.support.BucketFactory;
import freenet.support.SimpleFieldSet;
import freenet.support.io.PersistentTempBucketFactory;

public abstract class FCPMessage {

	public void send(OutputStream os) throws IOException {
		SimpleFieldSet sfs = getFieldSet();
		sfs.setEndMarker(getEndString());
		String msg = sfs.toString();
		os.write((getName()+"\n").getBytes("UTF-8"));
		os.write(msg.getBytes("UTF-8"));
	}

	String getEndString() {
		return "EndMessage";
	}
	
	public abstract SimpleFieldSet getFieldSet();

	public abstract String getName();
	
	/**
	 * Create a message from a SimpleFieldSet, and the message's name, if possible. 
	 */
	public static FCPMessage create(String name, SimpleFieldSet fs, BucketFactory bfTemp, PersistentTempBucketFactory bfPersistent) throws MessageInvalidException {
		if(name.equals(ClientHelloMessage.name))
			return new ClientHelloMessage(fs);
		if(name.equals(ClientGetMessage.name))
			return new ClientGetMessage(fs);
		if(name.equals(ClientPutMessage.name))
			return new ClientPutMessage(fs);
		if(name.equals(GenerateSSKMessage.name))
			return new GenerateSSKMessage(fs);
		if(name.equals(ListPeersMessage.name))
			return new ListPeersMessage(fs);
		if(name.equals(ListPersistentRequestsMessage.name))
			return new ListPersistentRequestsMessage(fs);
		if(name.equals(RemovePeer.name))
			return new RemovePeer(fs);
		if(name.equals(RemovePersistentRequest.name))
			return new RemovePersistentRequest(fs);
		if(name.equals(WatchGlobal.name))
			return new WatchGlobal(fs);
		if(name.equals(ModifyPeer.name))
			return new ModifyPeer(fs);
		if(name.equals(ModifyPersistentRequest.name))
			return new ModifyPersistentRequest(fs);
		if(name.equals(ClientPutDiskDirMessage.name))
			return new ClientPutDiskDirMessage(fs);
		if(name.equals(ClientPutComplexDirMessage.name))
			return new ClientPutComplexDirMessage(fs, bfTemp, bfPersistent);
		if(name.equals(SubscribeUSKMessage.name))
			return new SubscribeUSKMessage(fs);
		if(name.equals(GetRequestStatusMessage.name))
			return new GetRequestStatusMessage(fs);
		if(name.equals(ShutdownMessage.name))
			return new ShutdownMessage();
		if(name.equals("Void"))
			return null;
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "Unknown message name "+name, null);
//		if(name.equals("ClientPut"))
//			return new ClientPutFCPMessage(fs);
		// TODO Auto-generated method stub
	}

	/** Do whatever it is that we do with this type of message. 
	 * @throws MessageInvalidException */
	public abstract void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException;

}
