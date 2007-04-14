package freenet.node.fcp;

import java.io.IOException;
import java.io.OutputStream;

import freenet.node.Node;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.BucketFactory;
import freenet.support.io.PersistentTempBucketFactory;

public abstract class FCPMessage {

	public void send(OutputStream os) throws IOException {
		SimpleFieldSet sfs = getFieldSet();
		sfs.setEndMarker(getEndString());
		String msg = sfs.toString();
		os.write((getName()+ '\n').getBytes("UTF-8"));
		os.write(msg.getBytes("UTF-8"));
		if(Logger.shouldLog(Logger.DEBUG, this)) {
			Logger.debug(this, "Outgoing FCP message:\n"+getName()+'\n'+sfs.toString());
			Logger.debug(this, "Being handled by "+this);
		}
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
		if(name.equals(AddPeer.NAME))
			return new AddPeer(fs);
		if(name.equals(ClientGetMessage.NAME))
			return new ClientGetMessage(fs);
		if(name.equals(ClientHelloMessage.NAME))
			return new ClientHelloMessage(fs);
		if(name.equals(ClientPutComplexDirMessage.NAME))
			return new ClientPutComplexDirMessage(fs, bfTemp, bfPersistent);
		if(name.equals(ClientPutDiskDirMessage.NAME))
			return new ClientPutDiskDirMessage(fs);
		if(name.equals(ClientPutMessage.NAME))
			return new ClientPutMessage(fs);
		if(name.equals(GenerateSSKMessage.NAME))
			return new GenerateSSKMessage(fs);
		if(name.equals(GetConfig.NAME))
			return new GetConfig(fs);
		if(name.equals(GetNode.NAME))
			return new GetNode(fs);
		if(name.equals(GetRequestStatusMessage.NAME))
			return new GetRequestStatusMessage(fs);
		if(name.equals(ListPeersMessage.NAME))
			return new ListPeersMessage(fs);
		if(name.equals(ListPeerNotesMessage.NAME))
			return new ListPeerNotesMessage(fs);
		if(name.equals(ListPersistentRequestsMessage.NAME))
			return new ListPersistentRequestsMessage(fs);
		if(name.equals(ModifyConfig.NAME))
			return new ModifyConfig(fs);
		if(name.equals(ModifyPeer.NAME))
			return new ModifyPeer(fs);
		if(name.equals(ModifyPeerNote.NAME))
			return new ModifyPeerNote(fs);
		if(name.equals(ModifyPersistentRequest.NAME))
			return new ModifyPersistentRequest(fs);
		if(name.equals(RemovePeer.NAME))
			return new RemovePeer(fs);
		if(name.equals(RemovePersistentRequest.NAME))
			return new RemovePersistentRequest(fs);
		if(name.equals(ShutdownMessage.NAME))
			return new ShutdownMessage();
		if(name.equals(SubscribeUSKMessage.NAME))
			return new SubscribeUSKMessage(fs);
		if(name.equals(TestDDARequestMessage.NAME))
			return new TestDDARequestMessage(fs);
		if(name.equals(TestDDAResponseMessage.NAME))
			return new TestDDAResponseMessage(fs);
		if(name.equals(WatchGlobal.NAME))
			return new WatchGlobal(fs);
		if(name.equals("Void"))
			return null;
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "Unknown message name "+name, null, false);
	}
	
	/**
	 * Create a message from a SimpleFieldSet, and the message's name, if possible. 
	 * Usefull for FCPClients
	 */
	public static FCPMessage create(String name, SimpleFieldSet fs) throws MessageInvalidException {
		return FCPMessage.create(name, fs, null, null);
	}

	/** Do whatever it is that we do with this type of message. 
	 * @throws MessageInvalidException */
	public abstract void run(FCPConnectionHandler handler, Node node) throws MessageInvalidException;

}
