package freenet.node;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;

import freenet.client.DefaultMIMETypes;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.Peer;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.comm.RetrievalException;
import freenet.io.xfer.BulkReceiver;
import freenet.io.xfer.BulkTransmitter;
import freenet.io.xfer.PartiallyReceivedBulk;
import freenet.l10n.L10n;
import freenet.node.useralerts.N2NTMUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.Base64;
import freenet.support.Fields;
import freenet.support.HTMLNode;
import freenet.support.IllegalBase64Exception;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.io.FileUtil;
import freenet.support.io.RandomAccessFileWrapper;
import freenet.support.io.RandomAccessThing;

public class DarknetPeerNode extends PeerNode {

    /** Name of this node */
    String myName;
    
    /** True if this peer is not to be connected with */
    private boolean isDisabled;
    
    /** True if we don't send handshake requests to this peer, but will connect if we receive one */
    private boolean isListenOnly;
    
    /** True if we send handshake requests to this peer in infrequent bursts */
    private boolean isBurstOnly;
    
    /** True if we are currently sending this peer a burst of handshake requests */
    private boolean isBursting;

    /** True if we want to ignore the source port of the node's sent packets.
     * This is normally set when dealing with an Evil Corporate Firewall which rewrites the port on outgoing
     * packets but does not redirect incoming packets destined to the rewritten port.
     * What it does is this: If we have an address with the same IP but a different port, to the detectedPeer,
     * we use that instead. */
    private boolean ignoreSourcePort;
    
    /** True if we want to allow LAN/localhost addresses. */
    private boolean allowLocalAddresses;
    
    /** Extra peer data file numbers */
    private LinkedHashSet extraPeerDataFileNumbers;

    /** Private comment on the peer for /friends/ page */
    private String privateDarknetComment;
    
    /** Private comment on the peer for /friends/ page's extra peer data file number */
    private int privateDarknetCommentFileNumber;
    
    /** Queued-to-send N2NTM extra peer data file numbers */
    private LinkedHashSet queuedToSendN2NTMExtraPeerDataFileNumbers;

    /** Number of handshake attempts (while in ListenOnly mode) since the beginning of this burst */
    private int listeningHandshakeBurstCount;
    
    /** Total number of handshake attempts (while in ListenOnly mode) to be in this burst */
    private int listeningHandshakeBurstSize;
    
    private static boolean logMINOR;
    
    /**
     * Create a darknet PeerNode from a SimpleFieldSet
     * @param fs The SimpleFieldSet to parse
     * @param node2 The running Node we are part of.
     */
    public DarknetPeerNode(SimpleFieldSet fs, Node node2, NodeCrypto crypto, PeerManager peers, boolean fromLocal, OutgoingPacketMangler mangler) throws FSParseException, PeerParseException, ReferenceSignatureVerificationException {
    	super(fs, node2, crypto, peers, fromLocal, mangler, false);
    	
    	logMINOR = Logger.shouldLog(Logger.MINOR, this);
    	
    	long now = System.currentTimeMillis();
    	
        String name = fs.get("myName");
        if(name == null) throw new FSParseException("No name");
        myName = name;

        if(fromLocal) {
        	SimpleFieldSet metadata = fs.subset("metadata");
        	
        	isDisabled = Fields.stringToBool(metadata.get("isDisabled"), false);
        	isListenOnly = Fields.stringToBool(metadata.get("isListenOnly"), false);
        	isBurstOnly = Fields.stringToBool(metadata.get("isBurstOnly"), false);
        	ignoreSourcePort = Fields.stringToBool(metadata.get("ignoreSourcePort"), false);
        	allowLocalAddresses = Fields.stringToBool(metadata.get("allowLocalAddresses"), false);
        }
	
        listeningHandshakeBurstCount = 0;
        listeningHandshakeBurstSize = Node.MIN_BURSTING_HANDSHAKE_BURST_SIZE
        	+ node.random.nextInt(Node.RANDOMIZED_BURSTING_HANDSHAKE_BURST_SIZE);
        if(isBurstOnly) {
        	Logger.minor(this, "First BurstOnly mode handshake in "+(sendHandshakeTime - now)+"ms for "+getName()+" (count: "+listeningHandshakeBurstCount+", size: "+listeningHandshakeBurstSize+ ')');
        }

		// Setup the private darknet comment note
        privateDarknetComment = "";
        privateDarknetCommentFileNumber = -1;

		// Setup the extraPeerDataFileNumbers
		extraPeerDataFileNumbers = new LinkedHashSet();
		
		// Setup the queuedToSendN2NTMExtraPeerDataFileNumbers
		queuedToSendN2NTMExtraPeerDataFileNumbers = new LinkedHashSet();
        
        setPeerNodeStatus(now);

    }

    /**
     * 
     * Normally this is the address that packets have been received from from this node.
     * However, if ignoreSourcePort is set, we will search for a similar address with a different port 
     * number in the node reference.
     */
    public synchronized Peer getPeer(){
    	Peer detectedPeer = super.getPeer();
    	if(ignoreSourcePort) {
    		FreenetInetAddress addr = detectedPeer == null ? null : detectedPeer.getFreenetAddress();
    		int port = detectedPeer == null ? -1 : detectedPeer.getPort();
    		if(nominalPeer == null) return detectedPeer;
    		for(int i=0;i<nominalPeer.size();i++) {
    			Peer p = (Peer) nominalPeer.get(i);
    			if(p.getPort() != port && p.getFreenetAddress().equals(addr)) {
    				return p;
    			}
    		}
    	}
    	return detectedPeer;
    }

    /**
     * @return True, if we are disconnected and it has been a
     * sufficient time period since we last sent a handshake
     * attempt.
     */
    public boolean shouldSendHandshake() {
    	synchronized(this) {
    		if(isDisabled) return false;
    		if(isListenOnly) return false;
    		if(!super.shouldSendHandshake()) return false;
    		if(isBurstOnly())
    			isBursting = true;
    		else
    			return true;
    	}
		setPeerNodeStatus(System.currentTimeMillis());
		return true;
    }
    
    protected synchronized boolean innerProcessNewNoderef(SimpleFieldSet fs, boolean forARK) throws FSParseException {
    	boolean changedAnything = super.innerProcessNewNoderef(fs, forARK);
        String name = fs.get("myName");
        if(name != null && !name.equals(myName)) {
        	changedAnything = true;
            myName = name;
        }
        return changedAnything;
    }
    
    public synchronized SimpleFieldSet exportFieldSet() {
    	SimpleFieldSet fs = super.exportFieldSet();
    	fs.putSingle("myName", getName());
    	return fs;
    }
    	
    public synchronized SimpleFieldSet exportMetadataFieldSet() {
    	SimpleFieldSet fs = super.exportMetadataFieldSet();
    	if(isDisabled)
    		fs.putSingle("isDisabled", "true");
    	if(isListenOnly)
    		fs.putSingle("isListenOnly", "true");
    	if(isBurstOnly)
    		fs.putSingle("isBurstOnly", "true");
    	if(ignoreSourcePort)
    		fs.putSingle("ignoreSourcePort", "true");
    	if(allowLocalAddresses)
    		fs.putSingle("allowLocalAddresses", "true");
    	return fs;
    }

    
	public synchronized String getName() {
		return myName;
	}

	protected synchronized int getPeerNodeStatus(long now, long backedOffUntil) {
		if(isDisabled) {
			return PeerManager.PEER_NODE_STATUS_DISABLED;
		}
		int status = super.getPeerNodeStatus(now, backedOffUntil);
		if(status == PeerManager.PEER_NODE_STATUS_CONNECTED || 
				status == PeerManager.PEER_NODE_STATUS_CLOCK_PROBLEM ||
				status == PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF ||
				status == PeerManager.PEER_NODE_STATUS_TOO_NEW ||
				status == PeerManager.PEER_NODE_STATUS_TOO_OLD)
			return status;
		if(isListenOnly)
			return PeerManager.PEER_NODE_STATUS_LISTEN_ONLY;
		if(isBursting)
			return PeerManager.PEER_NODE_STATUS_BURSTING;
		if(isBurstOnly)
			return PeerManager.PEER_NODE_STATUS_LISTENING;
		return status;
	}
	
	public void enablePeer() {
		synchronized(this) {
			isDisabled = false;
		}
		setPeerNodeStatus(System.currentTimeMillis());
        node.peers.writePeers();
	}
	
	public void disablePeer() {
		synchronized(this) {
			isDisabled = true;
		}
		if(isConnected()) {
			forceDisconnect();
		}
		stopARKFetcher();
		setPeerNodeStatus(System.currentTimeMillis());
        node.peers.writePeers();
	}

	public synchronized boolean isDisabled() {
		return isDisabled;
	}
	
	public void setListenOnly(boolean setting) {
		synchronized(this) {
			isListenOnly = setting;
		}
		if(setting && isBurstOnly()) {
			setBurstOnly(false);
		}
		if(setting) {
			stopARKFetcher();
		}
		setPeerNodeStatus(System.currentTimeMillis());
        node.peers.writePeers();
	}

	public synchronized boolean isListenOnly() {
		return isListenOnly;
	}
	
	public void setBurstOnly(boolean setting) {
		synchronized(this) {
			isBurstOnly = setting;
		}
		if(setting && isListenOnly()) {
			setListenOnly(false);
		}
		long now = System.currentTimeMillis();
		if(!setting) {
			synchronized(this) {
				sendHandshakeTime = now;  // don't keep any long handshake delays we might have had under BurstOnly
			}
		}
		setPeerNodeStatus(now);
		node.peers.writePeers();
	}

	public void setIgnoreSourcePort(boolean setting) {
		synchronized(this) {
			ignoreSourcePort = setting;
		}
	}
	

	public boolean isIgnoreSourcePort() {
		return ignoreSourcePort;
	}
	
	public boolean isIgnoreSource() {
		return ignoreSourcePort;
	}
	
	public synchronized boolean isBurstOnly() {
		return isBurstOnly;
	}

	public synchronized boolean allowLocalAddresses() {
		return allowLocalAddresses;
	}

	public void setAllowLocalAddresses(boolean setting) {
		synchronized(this) {
			allowLocalAddresses = setting;
		}
        node.peers.writePeers();
	}
	
	public boolean readExtraPeerData() {
		String extraPeerDataDirPath = node.getExtraPeerDataDir();
		File extraPeerDataPeerDir = new File(extraPeerDataDirPath+File.separator+getIdentityString());
	 	if(!extraPeerDataPeerDir.exists()) {
	 		return false;
	 	}
	 	if(!extraPeerDataPeerDir.isDirectory()) {
	   		Logger.error(this, "Extra peer data directory for peer not a directory: "+extraPeerDataPeerDir.getPath());
	 		return false;
	 	}
	 	File[] extraPeerDataFiles = extraPeerDataPeerDir.listFiles();
	 	if(extraPeerDataFiles == null) {
	 		return false;
	 	}
		boolean gotError = false;
		boolean readResult = false;
		for (int i = 0; i < extraPeerDataFiles.length; i++) {
			Integer fileNumber;
			try {
				fileNumber = new Integer(extraPeerDataFiles[i].getName());
			} catch (NumberFormatException e) {
				gotError = true;
				continue;
			}
			synchronized(extraPeerDataFileNumbers) {
				extraPeerDataFileNumbers.add(fileNumber);
			}
			readResult = readExtraPeerDataFile(extraPeerDataFiles[i], fileNumber.intValue());
			if(!readResult) {
				gotError = true;
			}
		}
		return !gotError;
	}

	public boolean rereadExtraPeerDataFile(int fileNumber) {
		if(logMINOR)
			Logger.minor(this, "Rereading peer data file "+fileNumber+" for "+shortToString());
		String extraPeerDataDirPath = node.getExtraPeerDataDir();
		File extraPeerDataPeerDir = new File(extraPeerDataDirPath+File.separator+getIdentityString());
		if(!extraPeerDataPeerDir.exists()) {
			Logger.error(this, "Extra peer data directory for peer does not exist: "+extraPeerDataPeerDir.getPath());
			return false;
		}
		if(!extraPeerDataPeerDir.isDirectory()) {
			Logger.error(this, "Extra peer data directory for peer not a directory: "+extraPeerDataPeerDir.getPath());
			return false;
		}
		File extraPeerDataFile = new File(extraPeerDataDirPath+File.separator+getIdentityString()+File.separator+fileNumber);
		if(!extraPeerDataFile.exists()) {
			Logger.error(this, "Extra peer data file for peer does not exist: "+extraPeerDataFile.getPath());
			return false;
		}
		return readExtraPeerDataFile(extraPeerDataFile, fileNumber);
	}

	public boolean readExtraPeerDataFile(File extraPeerDataFile, int fileNumber) {
		if(logMINOR) Logger.minor(this, "Reading "+extraPeerDataFile+" : "+fileNumber+" for "+shortToString());
		boolean gotError = false;
	 	if(!extraPeerDataFile.exists()) {
	 		if(logMINOR)
	 			Logger.minor(this, "Does not exist");
	 		return false;
	 	}
		Logger.normal(this, "extraPeerDataFile: "+extraPeerDataFile.getPath());
		FileInputStream fis;
		try {
			fis = new FileInputStream(extraPeerDataFile);
		} catch (FileNotFoundException e1) {
			Logger.normal(this, "Extra peer data file not found: "+extraPeerDataFile.getPath());
			return false;
		}
		InputStreamReader isr;
		try {
			isr = new InputStreamReader(fis, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: "+e, e);
		}
		BufferedReader br = new BufferedReader(isr);
		SimpleFieldSet fs = null;
		try {
			// Read in the single SimpleFieldSet
			fs = new SimpleFieldSet(br, false, true);
		} catch (EOFException e3) {
			// End of file, fine
		} catch (IOException e4) {
			Logger.error(this, "Could not read extra peer data file: "+e4, e4);
		} finally {
			try {
				br.close();
			} catch (IOException e5) {
				Logger.error(this, "Ignoring "+e5+" caught reading "+extraPeerDataFile.getPath(), e5);
			}
		}
		if(fs == null) {
			Logger.normal(this, "Deleting corrupt (too short?) file: "+extraPeerDataFile);
			deleteExtraPeerDataFile(fileNumber);
			return true;
		}
		boolean parseResult = false;
		try {
			parseResult = parseExtraPeerData(fs, extraPeerDataFile, fileNumber);
			if(!parseResult) {
				gotError = true;
			}
		} catch (FSParseException e2) {
			Logger.error(this, "Could not parse extra peer data: "+e2+ '\n' +fs.toString(),e2);
			gotError = true;
		}
		return !gotError;
	}

	private boolean parseExtraPeerData(SimpleFieldSet fs, File extraPeerDataFile, int fileNumber) throws FSParseException {
		String extraPeerDataTypeString = fs.get("extraPeerDataType");
		int extraPeerDataType = -1;
		try {
			extraPeerDataType = Integer.parseInt(extraPeerDataTypeString);
		} catch (NumberFormatException e) {
			Logger.error(this, "NumberFormatException parsing extraPeerDataType ("+extraPeerDataTypeString+") in file "+extraPeerDataFile.getPath());
			return false;
		}
		if(extraPeerDataType == Node.EXTRA_PEER_DATA_TYPE_N2NTM) {
			node.handleNodeToNodeTextMessageSimpleFieldSet(fs, this, fileNumber);
			return true;
		} else if(extraPeerDataType == Node.EXTRA_PEER_DATA_TYPE_PEER_NOTE) {
			String peerNoteTypeString = fs.get("peerNoteType");
			int peerNoteType = -1;
			try {
				peerNoteType = Integer.parseInt(peerNoteTypeString);
			} catch (NumberFormatException e) {
				Logger.error(this, "NumberFormatException parsing peerNoteType ("+peerNoteTypeString+") in file "+extraPeerDataFile.getPath());
				return false;
			}
			if(peerNoteType == Node.PEER_NOTE_TYPE_PRIVATE_DARKNET_COMMENT) {
				synchronized(privateDarknetComment) {
				  	try {
						privateDarknetComment = new String(Base64.decode(fs.get("privateDarknetComment")));
					} catch (IllegalBase64Exception e) {
						Logger.error(this, "Bad Base64 encoding when decoding a private darknet comment SimpleFieldSet", e);
						return false;
					}
					privateDarknetCommentFileNumber = fileNumber;
				}
				return true;
			}
			Logger.error(this, "Read unknown peer note type '"+peerNoteType+"' from file "+extraPeerDataFile.getPath());
			return false;
		} else if(extraPeerDataType == Node.EXTRA_PEER_DATA_TYPE_QUEUED_TO_SEND_N2NTM) {
			boolean sendSuccess = false;
			int type = fs.getInt("n2nType", 1); // FIXME remove default
			fs.putOverwrite("n2nType", Integer.toString(type));
			if(isConnected()) {
				Message n2ntm;
				if(fs.get("extraPeerDataType") != null) {
					fs.removeValue("extraPeerDataType");
				}
				if(fs.get("senderFileNumber") != null) {
					fs.removeValue("senderFileNumber");
				}
				fs.putOverwrite("senderFileNumber", String.valueOf(fileNumber));
				if(fs.get("sentTime") != null) {
					fs.removeValue("sentTime");
				}
				fs.putOverwrite("sentTime", Long.toString(System.currentTimeMillis()));
				
				try {
					n2ntm = DMT.createNodeToNodeMessage(type, fs.toString().getBytes("UTF-8"));
				} catch (UnsupportedEncodingException e) {
					Logger.error(this, "UnsupportedEncodingException processing extraPeerDataType ("+extraPeerDataTypeString+") in file "+extraPeerDataFile.getPath(), e);
					return false;
				}

				try {
					synchronized(queuedToSendN2NTMExtraPeerDataFileNumbers) {
						node.usm.send(this, n2ntm, null);
						Logger.normal(this, "Sent queued ("+fileNumber+") N2NTM to '"+getName()+"': "+n2ntm);
						sendSuccess = true;
						queuedToSendN2NTMExtraPeerDataFileNumbers.remove(new Integer(fileNumber));
					}
					deleteExtraPeerDataFile(fileNumber);
				} catch (NotConnectedException e) {
					sendSuccess = false;  // redundant, but clear
				}
			}
			if(!sendSuccess) {
				synchronized(queuedToSendN2NTMExtraPeerDataFileNumbers) {
					fs.putOverwrite("extraPeerDataType", Integer.toString(extraPeerDataType));
					fs.removeValue("sentTime");
					queuedToSendN2NTMExtraPeerDataFileNumbers.add(new Integer(fileNumber));
				}
			}
			return true;
		}
		Logger.error(this, "Read unknown extra peer data type '"+extraPeerDataType+"' from file "+extraPeerDataFile.getPath());
		return false;
	}

	public int writeNewExtraPeerDataFile(SimpleFieldSet fs, int extraPeerDataType) {
		String extraPeerDataDirPath = node.getExtraPeerDataDir();
		if(extraPeerDataType > 0)
			fs.putOverwrite("extraPeerDataType", Integer.toString(extraPeerDataType));
		File extraPeerDataPeerDir = new File(extraPeerDataDirPath+File.separator+getIdentityString());
	 	if(!extraPeerDataPeerDir.exists()) {
	 		if(!extraPeerDataPeerDir.mkdir()) {
		   		Logger.error(this, "Extra peer data directory for peer could not be created: "+extraPeerDataPeerDir.getPath());
		 		return -1;
		 	}
	 	}
	 	if(!extraPeerDataPeerDir.isDirectory()) {
	   		Logger.error(this, "Extra peer data directory for peer not a directory: "+extraPeerDataPeerDir.getPath());
	 		return -1;
	 	}
		Integer[] localFileNumbers = null;
		int nextFileNumber = 0;
		synchronized(extraPeerDataFileNumbers) {
			// Find the first free slot
			localFileNumbers = (Integer[]) extraPeerDataFileNumbers.toArray(new Integer[extraPeerDataFileNumbers.size()]);
			Arrays.sort(localFileNumbers);
			for (int i = 0; i < localFileNumbers.length; i++) {
				if(localFileNumbers[i].intValue() > nextFileNumber) {
					break;
				}
				nextFileNumber = localFileNumbers[i].intValue() + 1;
			}
			extraPeerDataFileNumbers.add(new Integer(nextFileNumber));
		}
		FileOutputStream fos;
		File extraPeerDataFile = new File(extraPeerDataPeerDir.getPath()+File.separator+nextFileNumber);
	 	if(extraPeerDataFile.exists()) {
   			Logger.error(this, "Extra peer data file already exists: "+extraPeerDataFile.getPath());
		 	return -1;
	 	}
		String f = extraPeerDataFile.getPath();
		try {
			fos = new FileOutputStream(f);
		} catch (FileNotFoundException e2) {
			Logger.error(this, "Cannot write extra peer data file to disk: Cannot create "
					+ f + " - " + e2, e2);
			return -1;
		}
		OutputStreamWriter w;
		try {
			w = new OutputStreamWriter(fos, "UTF-8");
		} catch (UnsupportedEncodingException e2) {
			throw new Error("UTF-8 unsupported!: "+e2, e2);
		}
		BufferedWriter bw = new BufferedWriter(w);
		try {
			fs.writeTo(bw);
			bw.close();
		} catch (IOException e) {
			try {
				fos.close();
			} catch (IOException e1) {
				Logger.error(this, "Cannot close extra peer data file: "+e, e);
			}
			Logger.error(this, "Cannot write file: " + e, e);
			return -1;
		}
		return nextFileNumber;
	}

	public void deleteExtraPeerDataFile(int fileNumber) {
		String extraPeerDataDirPath = node.getExtraPeerDataDir();
		File extraPeerDataPeerDir = new File(extraPeerDataDirPath, getIdentityString());
	 	if(!extraPeerDataPeerDir.exists()) {
	   		Logger.error(this, "Extra peer data directory for peer does not exist: "+extraPeerDataPeerDir.getPath());
	 		return;
	 	}
	 	if(!extraPeerDataPeerDir.isDirectory()) {
	   		Logger.error(this, "Extra peer data directory for peer not a directory: "+extraPeerDataPeerDir.getPath());
	 		return;
	 	}
		File extraPeerDataFile = new File(extraPeerDataPeerDir, Integer.toString(fileNumber));
	 	if(!extraPeerDataFile.exists()) {
	   		Logger.error(this, "Extra peer data file for peer does not exist: "+extraPeerDataFile.getPath());
	 		return;
	 	}
		synchronized(extraPeerDataFileNumbers) {
			extraPeerDataFileNumbers.remove(new Integer(fileNumber));
		}
		if(!extraPeerDataFile.delete()) {
			if(extraPeerDataFile.exists()) {
				Logger.error(this, "Cannot delete file "+extraPeerDataFile+" after sending message to "+getPeer()+" - it may be resent on resting the node");
			} else {
				Logger.normal(this, "File does not exist when deleting: "+extraPeerDataFile+" after sending message to "+getPeer());
			}
		}
	}

	public void removeExtraPeerDataDir() {
		String extraPeerDataDirPath = node.getExtraPeerDataDir();
		File extraPeerDataPeerDir = new File(extraPeerDataDirPath+File.separator+getIdentityString());
	 	if(!extraPeerDataPeerDir.exists()) {
			Logger.error(this, "Extra peer data directory for peer does not exist: "+extraPeerDataPeerDir.getPath());
			return;
	 	}
	 	if(!extraPeerDataPeerDir.isDirectory()) {
	   		Logger.error(this, "Extra peer data directory for peer not a directory: "+extraPeerDataPeerDir.getPath());
	 		return;
	 	}
		Integer[] localFileNumbers = null;
		synchronized(extraPeerDataFileNumbers) {
			localFileNumbers = (Integer[]) extraPeerDataFileNumbers.toArray(new Integer[extraPeerDataFileNumbers.size()]);
		}
		for (int i = 0; i < localFileNumbers.length; i++) {
			deleteExtraPeerDataFile(localFileNumbers[i].intValue());
		}
		extraPeerDataPeerDir.delete();
	}

	public boolean rewriteExtraPeerDataFile(SimpleFieldSet fs, int extraPeerDataType, int fileNumber) {
		String extraPeerDataDirPath = node.getExtraPeerDataDir();
		if(extraPeerDataType > 0)
			fs.putOverwrite("extraPeerDataType", Integer.toString(extraPeerDataType));
		File extraPeerDataPeerDir = new File(extraPeerDataDirPath+File.separator+getIdentityString());
	 	if(!extraPeerDataPeerDir.exists()) {
	   		Logger.error(this, "Extra peer data directory for peer does not exist: "+extraPeerDataPeerDir.getPath());
	 		return false;
	 	}
	 	if(!extraPeerDataPeerDir.isDirectory()) {
	   		Logger.error(this, "Extra peer data directory for peer not a directory: "+extraPeerDataPeerDir.getPath());
	 		return false;
	 	}
		File extraPeerDataFile = new File(extraPeerDataDirPath+File.separator+getIdentityString()+File.separator+fileNumber);
	 	if(!extraPeerDataFile.exists()) {
	   		Logger.error(this, "Extra peer data file for peer does not exist: "+extraPeerDataFile.getPath());
	 		return false;
	 	}
		String f = extraPeerDataFile.getPath();
		FileOutputStream fos;
		try {
			fos = new FileOutputStream(f);
		} catch (FileNotFoundException e2) {
			Logger.error(this, "Cannot write extra peer data file to disk: Cannot open "
					+ f + " - " + e2, e2);
			return false;
		}
		OutputStreamWriter w;
		try {
			w = new OutputStreamWriter(fos, "UTF-8");
		} catch (UnsupportedEncodingException e2) {
			throw new Error("JVM doesn't support UTF-8 charset!: "+e2, e2);
		}
		BufferedWriter bw = new BufferedWriter(w);
		try {
			fs.writeTo(bw);
			bw.close();
		} catch (IOException e) {
			try {
				fos.close();
			} catch (IOException e1) {
				Logger.error(this, "Cannot close extra peer data file: "+e, e);
			}
			Logger.error(this, "Cannot write file: " + e, e);
			return false;
		}
		return true;
	}
	
	public synchronized String getPrivateDarknetCommentNote() {
		return privateDarknetComment;
	}
	
	public synchronized void setPrivateDarknetCommentNote(String comment) {
		int localFileNumber;
		synchronized(privateDarknetComment) {
			privateDarknetComment = comment;
			localFileNumber = privateDarknetCommentFileNumber;
		}
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("peerNoteType", Node.PEER_NOTE_TYPE_PRIVATE_DARKNET_COMMENT);
		fs.putSingle("privateDarknetComment", Base64.encode(comment.getBytes()));
		if(localFileNumber == -1) {
			localFileNumber = writeNewExtraPeerDataFile(fs, Node.EXTRA_PEER_DATA_TYPE_PEER_NOTE);
			synchronized(privateDarknetComment) {
				privateDarknetCommentFileNumber = localFileNumber;
			}
		} else {
			rewriteExtraPeerDataFile(fs, Node.EXTRA_PEER_DATA_TYPE_PEER_NOTE, localFileNumber);
		}
	}

	public void queueN2NTM(SimpleFieldSet fs) {
		int fileNumber = writeNewExtraPeerDataFile( fs, Node.EXTRA_PEER_DATA_TYPE_QUEUED_TO_SEND_N2NTM);
		synchronized(queuedToSendN2NTMExtraPeerDataFileNumbers) {
			queuedToSendN2NTMExtraPeerDataFileNumbers.add(new Integer(fileNumber));
		}
	}

	public void sendQueuedN2NTMs() {
		if(logMINOR)
			Logger.minor(this, "Sending queued N2NTMs for "+shortToString());
		Integer[] localFileNumbers = null;
		synchronized(queuedToSendN2NTMExtraPeerDataFileNumbers) {
			localFileNumbers = (Integer[]) queuedToSendN2NTMExtraPeerDataFileNumbers.toArray(new Integer[queuedToSendN2NTMExtraPeerDataFileNumbers.size()]);
		}
		Arrays.sort(localFileNumbers);
		for (int i = 0; i < localFileNumbers.length; i++) {
			rereadExtraPeerDataFile(localFileNumbers[i].intValue());
		}
	}

	void startARKFetcher() {
		synchronized(this) {
			if(isListenOnly) {
				Logger.minor(this, "Not starting ark fetcher for "+this+" as it's in listen-only mode.");
				return;
			}
		}
		super.startARKFetcher();
	}
	
	public String getTMCIPeerInfo() {
		return getName()+'\t'+super.getTMCIPeerInfo();
	}
	
	/**
	 * A method to be called once at the beginning of every time isConnected() is true
	 */
	protected void onConnect() {
		sendQueuedN2NTMs();
	}

	// File transfer offers
	// FIXME this should probably be somewhere else, along with the N2NTM stuff... but where?
	// FIXME this should be persistent across node restarts

	/** Files I have offered to this peer */
	private final HashMap myFileOffersByUID = new HashMap();
	/** Files this peer has offered to me */
	private final HashMap hisFileOffersByUID = new HashMap();
	
	private void storeOffers() {
		// FIXME do something
	}
	
	class FileOffer {
		final long uid;
		final String filename;
		final String mimeType;
		final String comment;
		private RandomAccessThing data;
		final long size;
		/** Who is offering it? True = I am offering it, False = I am being offered it */
		final boolean amIOffering;
		private PartiallyReceivedBulk prb;
		private BulkTransmitter transmitter;
		private BulkReceiver receiver;
		/** True if the offer has either been accepted or rejected */
		private boolean acceptedOrRejected;
		
		FileOffer(long uid, RandomAccessThing data, String filename, String mimeType, String comment) throws IOException {
			this.uid = uid;
			this.data = data;
			this.filename = filename;
			this.mimeType = mimeType;
			this.comment = comment;
			size = data.size();
			amIOffering = true;
		}

		public FileOffer(SimpleFieldSet fs, boolean amIOffering) throws FSParseException {
			uid = fs.getLong("uid");
			size = fs.getLong("size");
			mimeType = fs.get("metadata.contentType");
			filename = FileUtil.sanitize(fs.get("filename"), mimeType);
			String s = fs.get("comment");
			if(s != null) {
				try {
					s = new String(Base64.decode(s), "UTF-8");
				} catch (UnsupportedEncodingException e) {
					throw new Error(e);
				} catch (IllegalBase64Exception e) {
					// Maybe it wasn't encoded? FIXME remove
				}
			}
			comment = s;
			this.amIOffering = amIOffering;
		}

		public void toFieldSet(SimpleFieldSet fs) {
			fs.put("uid", uid);
			fs.putSingle("filename", filename);
			fs.putSingle("metadata.contentType", mimeType);
			try {
				fs.putSingle("comment", Base64.encode(comment.getBytes("UTF-8")));
			} catch (UnsupportedEncodingException e) {
				throw new Error(e);
			}
			fs.put("size", size);
		}

		public void accept() {
			acceptedOrRejected = true;
			File dest = new File(node.clientCore.downloadDir, "direct-"+FileUtil.sanitize(getName())+"-"+filename);
			try {
				data = new RandomAccessFileWrapper(dest, "rw");
			} catch (FileNotFoundException e) {
				// Impossible
				throw new Error("Impossible: FileNotFoundException opening with RAF with rw! "+e, e);
			}
			prb = new PartiallyReceivedBulk(node.usm, size, Node.PACKET_SIZE, data, false);
			receiver = new BulkReceiver(prb, DarknetPeerNode.this, uid);
			// FIXME make this persistent
			Thread t = new Thread(new Runnable() {
				public void run() {
					if(logMINOR)
						Logger.minor(this, "Received file");
					try {
						if(!receiver.receive()) {
							String err = "Failed to receive "+this;
							Logger.error(this, err);
							System.err.println(err);
							onReceiveFailure();
						} else {
							onReceiveSuccess();
						}
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t+" receiving file", t);
						onReceiveFailure();
					}
					if(logMINOR)
						Logger.minor(this, "Received file");
				}
			}, "Receiver for bulk transfer "+uid+":"+filename);
			t.setDaemon(true);
			t.start();
			if(logMINOR) Logger.minor(this, "Receiving on "+t);
			sendFileOfferAccepted(uid);
		}

		public void send() throws DisconnectedException {
			prb = new PartiallyReceivedBulk(node.usm, size, Node.PACKET_SIZE, data, true);
			transmitter = new BulkTransmitter(prb, DarknetPeerNode.this, uid, node.outputThrottle);
			if(logMINOR)
				Logger.minor(this, "Sending "+uid);
			Thread t = new Thread(new Runnable() {
				public void run() {
					if(logMINOR)
						Logger.minor(this, "Sending file");
					try {
						if(!transmitter.send()) {
							String err = "Failed to send "+uid+" for "+FileOffer.this;
							Logger.error(this, err);
							System.err.println(err);
						}
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t+" sending file", t);
					}
					if(logMINOR)
						Logger.minor(this, "Sent file");
				}

			}, "Sender for bulk transfer "+uid+":"+filename);
			t.setDaemon(true);
			t.start();
		}

		public void reject() {
			acceptedOrRejected = true;
			sendFileOfferRejected(uid);
		}

		public void onRejected() {
			transmitter.cancel();
			// FIXME prb's can't be shared, right? Well they aren't here...
			prb.abort(RetrievalException.CANCELLED_BY_RECEIVER, "Cancelled by receiver");
		}

		protected void onReceiveFailure() {
			UserAlert alert = new UserAlert() {
				public String dismissButtonText() {
					return L10n.getString("UserAlert.hide");
				}
				public HTMLNode getHTMLText() {
					HTMLNode div = new HTMLNode("div");
					
					div.addChild("p", l10n("failedReceiveHeader", new String[] { "filename", "node" },
							new String[] { filename, getName() }));
					
					// Descriptive table
					
					HTMLNode table = div.addChild("table", "border", "0");
					HTMLNode row = table.addChild("tr");
					row.addChild("td").addChild("#", l10n("fileLabel"));
					row.addChild("td").addChild("#", filename);
					row = table.addChild("tr");
					row.addChild("td").addChild("#", l10n("sizeLabel"));
					row.addChild("td").addChild("#", SizeUtil.formatSize(size));
					row = table.addChild("tr");
					row.addChild("td").addChild("#", l10n("mimeLabel"));
					row.addChild("td").addChild("#", mimeType);
					row = table.addChild("tr");
					row.addChild("td").addChild("#", l10n("senderLabel"));
					row.addChild("td").addChild("#", getName());
					row = table.addChild("tr");
					if(comment != null && comment.length() > 0) {
						row.addChild("td").addChild("#", l10n("commentLabel"));
						addComment(row.addChild("td"));
					}
					
					return div;
				}

				public short getPriorityClass() {
					return UserAlert.MINOR;
				}

				public String getText() {
					StringBuffer sb = new StringBuffer();
					sb.append(l10n("failedReceiveHeader", new String[] { "filename", "node" },
							new String[] { filename, getName() }));
					sb.append('\n');
					sb.append(l10n("fileLabel"));
					sb.append(' ');
					sb.append(filename);
					sb.append('\n');
					sb.append(l10n("sizeLabel"));
					sb.append(' ');
					sb.append(SizeUtil.formatSize(size));
					sb.append('\n');
					sb.append(l10n("mimeLabel"));
					sb.append(' ');
					sb.append(mimeType);
					sb.append('\n');
					sb.append(l10n("senderLabel"));
					sb.append(' ');
					sb.append(getName());
					sb.append('\n');
					if(comment != null && comment.length() > 0) {
						sb.append(l10n("commentLabel"));
						sb.append(' ');
						sb.append(comment);
					}
					return sb.toString();
				}

				public String getTitle() {
					return l10n("failedReceiveTitle");
				}

				public boolean isValid() {
					return true;
				}

				public void isValid(boolean validity) {
					// Ignore
				}

				public void onDismiss() {
					// Ignore
				}

				public boolean shouldUnregisterOnDismiss() {
					return true;
				}

				public boolean userCanDismiss() {
					return true;
				}
				
			};
			node.clientCore.alerts.register(alert);
		}

		private void onReceiveSuccess() {
			UserAlert alert = new UserAlert() {
				public String dismissButtonText() {
					return L10n.getString("UserAlert.hide");
				}
				public HTMLNode getHTMLText() {
					HTMLNode div = new HTMLNode("div");
					
					// FIXME localise!!!
					
					div.addChild("p", l10n("succeededReceiveHeader", new String[] { "filename", "node" },
							new String[] { filename, getName() }));
					
					// Descriptive table
					
					HTMLNode table = div.addChild("table", "border", "0");
					HTMLNode row = table.addChild("tr");
					row.addChild("td").addChild("#", l10n("fileLabel"));
					row.addChild("td").addChild("#", filename);
					row = table.addChild("tr");
					row.addChild("td").addChild("#", l10n("sizeLabel"));
					row.addChild("td").addChild("#", SizeUtil.formatSize(size));
					row = table.addChild("tr");
					row.addChild("td").addChild("#", l10n("mimeLabel"));
					row.addChild("td").addChild("#", mimeType);
					row = table.addChild("tr");
					row.addChild("td").addChild("#", l10n("senderLabel"));
					row.addChild("td").addChild("#", getName());
					row = table.addChild("tr");
					if(comment != null && comment.length() > 0) {
						row.addChild("td").addChild("#", l10n("commentLabel"));
						addComment(row.addChild("td"));
					}
					
					return div;
				}

				public short getPriorityClass() {
					return UserAlert.MINOR;
				}

				public String getText() {
					StringBuffer sb = new StringBuffer();
					sb.append(l10n("succeededReceiveHeader", new String[] { "filename", "node" },
							new String[] { filename, getName() }));
					sb.append('\n');
					sb.append(l10n("fileLabel"));
					sb.append(' ');
					sb.append(filename);
					sb.append('\n');
					sb.append(l10n("sizeLabel"));
					sb.append(' ');
					sb.append(SizeUtil.formatSize(size));
					sb.append('\n');
					sb.append(l10n("mimeLabel"));
					sb.append(' ');
					sb.append(mimeType);
					sb.append('\n');
					sb.append(l10n("senderLabel"));
					sb.append(' ');
					sb.append(userToString());
					sb.append('\n');
					if(comment != null && comment.length() > 0) {
						sb.append(l10n("commentLabel"));
						sb.append(' ');
						sb.append(comment);
					}
					return sb.toString();
				}

				public String getTitle() {
					return l10n("succeededReceiveTitle");
				}

				public boolean isValid() {
					return true;
				}

				public void isValid(boolean validity) {
					// Ignore
				}

				public void onDismiss() {
					// Ignore
				}

				public boolean shouldUnregisterOnDismiss() {
					return true;
				}

				public boolean userCanDismiss() {
					return true;
				}
				
			};
			node.clientCore.alerts.register(alert);
		}

		
		/** Ask the user whether (s)he wants to download a file from a direct peer */
		public UserAlert askUserUserAlert() {
			return new UserAlert() {
				public String dismissButtonText() {
					return null; // Cannot hide, but can reject
				}
				public HTMLNode getHTMLText() {
					HTMLNode div = new HTMLNode("div");
					
					div.addChild("p", l10n("offeredFileHeader", "name", getName()));
					
					// Descriptive table
					
					HTMLNode table = div.addChild("table", "border", "0");
					HTMLNode row = table.addChild("tr");
					row.addChild("td").addChild("#", l10n("fileLabel"));
					row.addChild("td").addChild("#", filename);
					row = table.addChild("tr");
					row.addChild("td").addChild("#", l10n("sizeLabel"));
					row.addChild("td").addChild("#", SizeUtil.formatSize(size));
					row = table.addChild("tr");
					row.addChild("td").addChild("#", l10n("mimeLabel"));
					row.addChild("td").addChild("#", mimeType);
					row = table.addChild("tr");
					row.addChild("td").addChild("#", l10n("senderLabel"));
					row.addChild("td").addChild("#", getName());
					row = table.addChild("tr");
					if(comment != null && comment.length() > 0) {
						row.addChild("td").addChild("#", l10n("commentLabel"));
						addComment(row.addChild("td"));
					}
					
					// Accept/reject form
					
					// Hopefully we will have a container when this function is called!
					HTMLNode form = node.clientCore.getToadletContainer().addFormChild(div, "/friends/", "f2fFileOfferAcceptForm");
					
					// FIXME node_ is inefficient
					form.addChild("input", new String[] { "type", "name" },
							new String[] { "hidden", "node_"+DarknetPeerNode.this.hashCode() });

					form.addChild("input", new String[] { "type", "name", "value" },
							new String[] { "hidden", "id", Long.toString(uid) });
					
					form.addChild("input", new String[] { "type", "name", "value" }, 
							new String[] { "submit", "acceptTransfer", l10n("acceptTransferButton") });

					form.addChild("input", new String[] { "type", "name", "value" }, 
							new String[] { "submit", "rejectTransfer", l10n("rejectTransferButton") });
					
					return div;
				}
				public short getPriorityClass() {
					return UserAlert.MINOR;
				}
				public String getText() {
					StringBuffer sb = new StringBuffer();
					sb.append(l10n("offeredFileHeader", "name", getName()));
					sb.append('\n');
					sb.append(l10n("fileLabel"));
					sb.append(' ');
					sb.append(filename);
					sb.append('\n');
					sb.append(l10n("sizeLabel"));
					sb.append(' ');
					sb.append(SizeUtil.formatSize(size));
					sb.append('\n');
					sb.append(l10n("mimeLabel"));
					sb.append(' ');
					sb.append(mimeType);
					sb.append('\n');
					sb.append(l10n("senderLabel"));
					sb.append(' ');
					sb.append(userToString());
					sb.append('\n');
					if(comment != null && comment.length() > 0) {
						sb.append(l10n("commentLabel"));
						sb.append(' ');
						sb.append(comment);
					}
					return sb.toString();
				}
				public String getTitle() {
					return l10n("askUserTitle");
				}

				public boolean isValid() {
					if(acceptedOrRejected) {
						node.clientCore.alerts.unregister(this);
						return false;
					}
					return true;
				}
				public void isValid(boolean validity) {
					// Ignore
				}
				public void onDismiss() {
					// Ignore
				}
				public boolean shouldUnregisterOnDismiss() {
					return false;
				}

				public boolean userCanDismiss() {
					return false; // should accept or reject
				}
			};
			
		}
		protected void addComment(HTMLNode node) {
			String[] lines = comment.split("\n");
			for (int i = 0, c = lines.length; i < c; i++) {
				node.addChild("div", lines[i]);
			}
		}

		private String l10n(String key) {
			return L10n.getString("FileOffer."+key);
		}
		private String l10n(String key, String pattern, String value) {
			return L10n.getString("FileOffer."+key, pattern, value);
		}
		private String l10n(String key, String[] pattern, String[] value) {
			return L10n.getString("FileOffer."+key, pattern, value);
		}
	}

	public int sendTextMessage(String message) {
		long now = System.currentTimeMillis();
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("n2nType", Node.N2N_MESSAGE_TYPE_FPROXY);
		fs.put("type", Node.N2N_TEXT_MESSAGE_TYPE_USERALERT);
		try {
			fs.putSingle("source_nodename", Base64.encode(node.getMyName().getBytes("UTF-8")));
			fs.putSingle("target_nodename", Base64.encode(getName().getBytes("UTF-8")));
			fs.putSingle("text", Base64.encode(message.getBytes("UTF-8")));
			fs.put("composedTime", now);
			fs.put("sentTime", now);
			Message n2ntm;
			n2ntm = DMT.createNodeToNodeMessage(
					Node.N2N_MESSAGE_TYPE_FPROXY, fs
							.toString().getBytes("UTF-8"));
			try {
				sendAsync(n2ntm, null, 0, null);
			} catch (NotConnectedException e) {
				fs.removeValue("sentTime");
				queueN2NTM(fs);
				setPeerNodeStatus(System.currentTimeMillis());
				return getPeerNodeStatus();
			}
			this.setPeerNodeStatus(System.currentTimeMillis());
			return getPeerNodeStatus();
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: "+e, e);
		}
	}

	public int sendFileOfferAccepted(long uid) {
		storeOffers();
		long now = System.currentTimeMillis();
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("n2nType", Node.N2N_MESSAGE_TYPE_FPROXY);
		fs.put("type", Node.N2N_TEXT_MESSAGE_TYPE_FILE_OFFER_ACCEPTED);
		try {
			fs.putSingle("source_nodename", Base64.encode(node.getMyName().getBytes("UTF-8")));
			fs.putSingle("target_nodename", Base64.encode(getName().getBytes("UTF-8")));
			fs.put("composedTime", now);
			fs.put("sentTime", now);
			fs.put("uid", uid);
			if(logMINOR)
				Logger.minor(this, "Sending node to node message (file offer accepted):\n"+fs);
			Message n2ntm;
			n2ntm = DMT.createNodeToNodeMessage(
					Node.N2N_MESSAGE_TYPE_FPROXY, fs
							.toString().getBytes("UTF-8"));
			try {
				sendAsync(n2ntm, null, 0, null);
			} catch (NotConnectedException e) {
				fs.removeValue("sentTime");
				queueN2NTM(fs);
				setPeerNodeStatus(System.currentTimeMillis());
				return getPeerNodeStatus();
			}
			this.setPeerNodeStatus(System.currentTimeMillis());
			return getPeerNodeStatus();
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: "+e, e);
		}
	}

	public int sendFileOfferRejected(long uid) {
		storeOffers();
		long now = System.currentTimeMillis();
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("n2nType", Node.N2N_MESSAGE_TYPE_FPROXY);
		fs.put("type", Node.N2N_TEXT_MESSAGE_TYPE_FILE_OFFER_REJECTED);
		try {
			fs.putSingle("source_nodename", Base64.encode(node.getMyName().getBytes("UTF-8")));
			fs.putSingle("target_nodename", Base64.encode(getName().getBytes("UTF-8")));
			fs.put("composedTime", now);
			fs.put("sentTime", now);
			fs.put("uid", uid);
			if(logMINOR)
				Logger.minor(this, "Sending node to node message (file offer rejected):\n"+fs);
			Message n2ntm;
			n2ntm = DMT.createNodeToNodeMessage(
					Node.N2N_MESSAGE_TYPE_FPROXY, fs
							.toString().getBytes("UTF-8"));
			try {
				sendAsync(n2ntm, null, 0, null);
			} catch (NotConnectedException e) {
				fs.removeValue("sentTime");
				queueN2NTM(fs);
				setPeerNodeStatus(System.currentTimeMillis());
				return getPeerNodeStatus();
			}
			this.setPeerNodeStatus(System.currentTimeMillis());
			return getPeerNodeStatus();
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: "+e, e);
		}
	}

	public int sendFileOffer(File filename, String message) throws IOException {
		String fnam = filename.getName();
		String mime = DefaultMIMETypes.guessMIMEType(fnam, false);
		long uid = node.random.nextLong();
		RandomAccessThing data = new RandomAccessFileWrapper(filename, "r");
		FileOffer fo = new FileOffer(uid, data, fnam, mime, message);
		synchronized(this) {
			myFileOffersByUID.put(new Long(uid), fo);
		}
		storeOffers();
		long now = System.currentTimeMillis();
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("n2nType", Node.N2N_MESSAGE_TYPE_FPROXY);
		fs.put("type", Node.N2N_TEXT_MESSAGE_TYPE_FILE_OFFER);
		try {
			fs.putSingle("source_nodename", Base64.encode(node.getMyName().getBytes("UTF-8")));
			fs.putSingle("target_nodename", Base64.encode(getName().getBytes("UTF-8")));
			fs.put("composedTime", now);
			fs.put("sentTime", now);
			fo.toFieldSet(fs);
			if(logMINOR)
				Logger.minor(this, "Sending node to node message (file offer):\n"+fs);
			Message n2ntm;
			int status = getPeerNodeStatus();
			n2ntm = DMT.createNodeToNodeMessage(
					Node.N2N_MESSAGE_TYPE_FPROXY, fs
							.toString().getBytes("UTF-8"));
			try {
				sendAsync(n2ntm, null, 0, null);
			} catch (NotConnectedException e) {
				fs.removeValue("sentTime");
				queueN2NTM(fs);
				setPeerNodeStatus(System.currentTimeMillis());
				return getPeerNodeStatus();
			}
			return status;
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: "+e, e);
		}
	}

	public void handleFproxyN2NTM(SimpleFieldSet fs, int fileNumber) {
		String source_nodename = null;
		String target_nodename = null;
		String text = null;
		long composedTime;
		long sentTime;
		long receivedTime;
	  	try {
			source_nodename = new String(Base64.decode(fs.get("source_nodename")));
			target_nodename = new String(Base64.decode(fs.get("target_nodename")));
			text = new String(Base64.decode(fs.get("text")));
			composedTime = fs.getLong("composedTime", -1);
			sentTime = fs.getLong("sentTime", -1);
			receivedTime = fs.getLong("receivedTime", -1);
		} catch (IllegalBase64Exception e) {
			Logger.error(this, "Bad Base64 encoding when decoding a N2NTM SimpleFieldSet", e);
			return;
		}
		N2NTMUserAlert userAlert = new N2NTMUserAlert(this, source_nodename, target_nodename, text, fileNumber, composedTime, sentTime, receivedTime);
		node.clientCore.alerts.register(userAlert);
	}

	public void handleFproxyFileOffer(SimpleFieldSet fs, int fileNumber) {
		final FileOffer offer;
		try {
			offer = new FileOffer(fs, false);
		} catch (FSParseException e) {
			Logger.error(this, "Could not parse offer: "+e+" on "+this+" :\n"+fs, e);
			return;
		}
		Long u = new Long(offer.uid);
		synchronized(this) {
			if(hisFileOffersByUID.containsKey(u)) return; // Ignore re-advertisement
			hisFileOffersByUID.put(u, offer);
		}
		
		// Don't persist for now - FIXME
		this.deleteExtraPeerDataFile(fileNumber);
		
		UserAlert alert = offer.askUserUserAlert();
			
		node.clientCore.alerts.register(alert);
	}

	public void acceptTransfer(long id) {
		if(logMINOR)
			Logger.minor(this, "Accepting transfer "+id+" on "+this);
		FileOffer fo;
		synchronized(this) {
			fo = (FileOffer) hisFileOffersByUID.get(new Long(id));
		}
		fo.accept();
	}
	
	public void rejectTransfer(long id) {
		FileOffer fo;
		synchronized(this) {
			fo = (FileOffer) hisFileOffersByUID.remove(new Long(id));
		}
		fo.reject();
	}
	
	public void handleFproxyFileOfferAccepted(SimpleFieldSet fs, int fileNumber) {
		// Don't persist for now - FIXME
		this.deleteExtraPeerDataFile(fileNumber);
		
		long uid;
		try {
			uid = fs.getLong("uid");
		} catch (FSParseException e) {
			Logger.error(this, "Could not parse offer accepted: "+e+" on "+this+" :\n"+fs, e);
			return;
		}
		if(logMINOR)
			Logger.minor(this, "Offer accepted for "+uid);
		Long u = new Long(uid);
		FileOffer fo;
		synchronized(this) {
			fo = (FileOffer) (myFileOffersByUID.get(u));
		}
		if(fo == null) {
			Logger.error(this, "No such offer: "+uid);
			try {
				sendAsync(DMT.createFNPBulkSendAborted(uid), null, fileNumber, null);
			} catch (NotConnectedException e) {
				// Fine by me!
			}
			return;
		}
		try {
			fo.send();
		} catch (DisconnectedException e) {
			Logger.error(this, "Cannot send because node disconnected: "+e+" for "+uid+":"+fo.filename, e);
		}
	}

	public void handleFproxyFileOfferRejected(SimpleFieldSet fs, int fileNumber) {
		// Don't persist for now - FIXME
		this.deleteExtraPeerDataFile(fileNumber);
		
		long uid;
		try {
			uid = fs.getLong("uid");
		} catch (FSParseException e) {
			Logger.error(this, "Could not parse offer rejected: "+e+" on "+this+" :\n"+fs, e);
			return;
		}
		
		FileOffer fo;
		synchronized(this) {
			fo = (FileOffer) myFileOffersByUID.remove(new Long(uid));
		}
		fo.onRejected();
	}

	public String userToString() {
		return ""+getPeer()+" : "+getName();
	}

	protected synchronized boolean innerCalcNextHandshake(boolean successfulHandshakeSend, boolean dontFetchARK, long now) {
		boolean fetchARKFlag = false;
		if(isBurstOnly) {
			listeningHandshakeBurstCount++;
			if(verifiedIncompatibleOlderVersion || verifiedIncompatibleNewerVersion) { 
				// Let them know we're here, but have no hope of connecting
				// Send one packet only.
				listeningHandshakeBurstCount = 0;
			} else if(listeningHandshakeBurstCount >= listeningHandshakeBurstSize) {
				listeningHandshakeBurstCount = 0;
				fetchARKFlag = true;
			}
			if(listeningHandshakeBurstCount == 0) {  // 0 only if we just reset it above
				sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS
					+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_BURSTING_HANDSHAKE_BURSTS);
				listeningHandshakeBurstSize = Node.MIN_BURSTING_HANDSHAKE_BURST_SIZE
						+ node.random.nextInt(Node.RANDOMIZED_BURSTING_HANDSHAKE_BURST_SIZE);
				isBursting = false;
			} else {
				sendHandshakeTime = now + Node.MIN_TIME_BETWEEN_HANDSHAKE_SENDS
					+ node.random.nextInt(Node.RANDOMIZED_TIME_BETWEEN_HANDSHAKE_SENDS);
			}
			if(logMINOR) Logger.minor(this, "Next BurstOnly mode handshake in "+(sendHandshakeTime - now)+"ms for "+getName()+" (count: "+listeningHandshakeBurstCount+", size: "+listeningHandshakeBurstSize+ ')', new Exception("double-called debug"));
		} else {
			super.innerCalcNextHandshake(successfulHandshakeSend, dontFetchARK, now);
		}
		return fetchARKFlag;
	}

	public PeerNodeStatus getStatus() {
		return new DarknetPeerNodeStatus(this);
	}

	public boolean isOpennet() {
		return false;
	}
}
