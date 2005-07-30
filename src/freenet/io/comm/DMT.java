/*
 * Dijjer - A Peer to Peer HTTP Cache
 * Copyright (C) 2004,2005 Change.Tv, Inc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package freenet.io.comm;

import freenet.*;
import freenet.keys.Key;
import freenet.keys.Key;
import freenet.support.*;

import java.util.*;


/**
 * @author ian
 *
 * To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class DMT {

	public static final String UID = "uid";
	public static final String SEND_TIME = "sendTime";
	public static final String EXTERNAL_ADDRESS = "externalAddress";
	public static final String BUILD = "build";
	public static final String FIRST_GOOD_BUILD = "firstGoodBuild";
	public static final String JOINER = "joiner";
	public static final String REASON = "reason";
	public static final String DESCRIPTION = "description";
	public static final String TTL = "ttl";
	public static final String PEERS = "peers";
	public static final String URL = "url";
	public static final String FORWARDERS = "forwarders";
	public static final String FILE_LENGTH = "fileLength";
	public static final String LAST_MODIFIED = "lastModified";
	public static final String CHUNK_NO = "chunkNo";
	public static final String DATA_SOURCE = "dataSource";
	public static final String CACHED = "cached";
	public static final String PACKET_NO = "packetNo";
	public static final String DATA = "data";
	public static final String IS_HASH = "isHash";
	public static final String HASH = "hash";
	public static final String SENT = "sent";
	public static final String MISSING = "missing";
	public static final String KEY = "key";
	public static final String CHK_HEADER = "chkHeader";
	public static final String FREENET_URI = "freenetURI";
	public static final String FREENET_ROUTING_KEY = "freenetRoutingKey";
    public static final String TEST_CHK_HEADERS = "testCHKHeaders";
    public static final String HTL = "hopsToLive";
    public static final String SUCCESS = "success";
    public static final String FNP_SOURCE_PEERNODE = "sourcePeerNode";
    public static final String PING_SEQNO = "pingSequenceNumber";
    public static final String LOCATION = "location";
    public static final String TARGET_LOCATION = "targetLocation";
    public static final String TYPE = "type";
    public static final String PAYLOAD = "payload";
    public static final String COUNTER = "counter";
    public static final String RETURN_LOCATION = "returnLocation";
    public static final String BLOCK_HEADERS = "blockHeaders";
    public static final String DATA_INSERT_REJECTED_REASON = "dataInsertRejectedReason";

	//Diagnostic
	public static final MessageType ping = new MessageType("ping") {{
		addField(SEND_TIME, Long.class);
	}};

	public static final Message createPing() {
		return createPing(System.currentTimeMillis());
	}
	
	public static final Message createPing(long sendTime) {
		Message msg = new Message(ping);
		msg.set(SEND_TIME, sendTime);
		return msg;
	}

	public static final MessageType pong = new MessageType("pong") {{
		addField(SEND_TIME, Long.class);
	}};

	public static final Message createPong(Message ping) {
		if (ping.isSet(SEND_TIME)) {
			return createPong(ping.getLong(SEND_TIME));
		} else {
			return createPong(500);
		}
	}
	
	public static final Message createPong(long sendTime) {
		Message msg = new Message(pong);
		msg.set(SEND_TIME, sendTime);
		return msg;
	}

	public static final MessageType whoAreYou = new MessageType("whoAreYou") {{
	}};

	public static final Message createWhoAreYou() {
		return new Message(whoAreYou);
	}

	public static final MessageType introduce = new MessageType("introduce") {{
		addField(EXTERNAL_ADDRESS, Peer.class);
		addField(BUILD, Integer.class);
		addField(FIRST_GOOD_BUILD, Integer.class);
	}};
	
	public static final Message createIntroduce(Peer externalAddress) {
		Message msg = new Message(introduce);
		msg.set(EXTERNAL_ADDRESS, externalAddress);
//		msg.set(BUILD, Dijjer.BUILD);
//		msg.set(FIRST_GOOD_BUILD, Dijjer.FIRST_GOOD_BUILD);
		return msg;
	}

	public static MessageType rejectDueToLoop = new MessageType("rejectDueToLoop") {{ 
		addField(UID, Long.class);
	}};
	
	public static final Message createRejectDueToLoop(long uid) {
		Message msg = new Message(rejectDueToLoop);
		msg.set(UID, uid);
		return msg;
	}
	
	// Assimilation
	public static final MessageType joinRequest = new MessageType("joinRequest") {{
		addField(UID, Long.class);
		addField(JOINER, Peer.class);
		addField(TTL, Integer.class);
	}};
	
	public static final Message createJoinRequest(long uid, Peer joiner, int ttl) {
		Message msg = new Message(joinRequest);
		msg.set(UID, uid);
		msg.set(JOINER, joiner);
		msg.set(TTL, ttl);
		return msg;
	}
	
	public static final MessageType joinRequestAck = new MessageType("joinRequestAck") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createJoinRequestAck(long uid) {
		Message msg = new Message(joinRequestAck);
		msg.set(UID, uid);
		return msg;
	}
	
	public static final MessageType joinResponse = new MessageType("joinResponse") {{
		addField(UID, Long.class);
		addLinkedListField(PEERS, Peer.class);
	}};
	
	public static final Message createJoinResponse(long uid, List peers) {
		Message msg = new Message(joinResponse);
		msg.set(UID, uid);
		msg.set(PEERS, peers);
		return msg;
	}	
	
	public static final MessageType disconnectNotification = new MessageType("disconnectNotification") {{ 
		addField(REASON, String.class);
	}};
	
	public static final Message createDisconnectNotification(String reason) {
		Message msg = new Message(disconnectNotification);
		msg.set(REASON, reason);
		return msg;
	}
	
	// Data search
	public static final MessageType requestData = new MessageType("requestData") {{
		addField(UID, Long.class);
		addField(URL, String.class);
		addLinkedListField(FORWARDERS, Peer.class);
		addField(FILE_LENGTH, Long.class);
		addField(LAST_MODIFIED, String.class);
		addField(CHUNK_NO, Integer.class);
		addField(TTL, Integer.class);
	}};
	
	public static final Message createRequestData(long uid, String url, List forwarders, long fileLength, String lastModified, int chunkNo, int ttl) {
		Message msg = new Message(requestData);
		msg.set(UID, uid);
		msg.set(URL, url);
		msg.set(FORWARDERS, forwarders);
		msg.set(FILE_LENGTH, fileLength);
		msg.set(LAST_MODIFIED, lastModified);
		msg.set(CHUNK_NO, chunkNo);
		msg.set(TTL, ttl);
		return msg;
	}

	public static final MessageType acknowledgeRequest = new MessageType("acknowledgeRequest") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createAcknowledgeRequest(long uid) {
		Message msg = new Message(acknowledgeRequest);
		msg.set(UID, uid);
		return msg;
	}
	
	public static final MessageType requestSuccessful = new MessageType("requestSuccessful") {{
		addField(UID, Long.class);
		addField(DATA_SOURCE, Peer.class);
		addField(CACHED, Boolean.class);
	}};

	public static final Message createRequestSuccessful(long uid, Peer dataSource, boolean cached) {
		Message msg = new Message(requestSuccessful);
		msg.set(UID, uid);
		msg.set(DATA_SOURCE, dataSource);
		msg.set(CACHED, cached);
		return msg;
	}

	public static final MessageType requestFailed = new MessageType("requestFailed") {{
		addField(UID, Long.class);
		addField(REASON, Integer.class);
		addField(DESCRIPTION, String.class);
	}};
	
	public static final Message createRequestFailed(long uid, int reason, String description) {
		Message msg = new Message(requestFailed);
		msg.set(UID, uid);
		msg.set(REASON, reason);
		msg.set(DESCRIPTION, description);
		return msg;
	}
	
	// Hash search
	public static final MessageType requestHash = new MessageType("requestHash") {{
		addField(UID, Long.class);
		addField(URL, String.class);
		addField(FILE_LENGTH, Long.class);
		addField(LAST_MODIFIED, String.class);
		addField(CHUNK_NO, Integer.class);
		addField(TTL, Integer.class);
	}};
	
	public static final Message createRequestHash(long uid, String url, long fileLength, String lastModified, int chunkNo, int ttl) {
		Message msg = new Message(requestHash);
		msg.set(UID, uid);
		msg.set(URL, url);
		msg.set(FILE_LENGTH, fileLength);
		msg.set(LAST_MODIFIED, lastModified);
		msg.set(CHUNK_NO, chunkNo);
		msg.set(TTL, ttl);
		return msg;
	}
	
	public static final MessageType requestHashAck = new MessageType("requestHashAck") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createRequestHashAck(long uid) {
		Message msg = new Message(requestHashAck);
		msg.set(UID, uid);
		return msg;
	}
	
	// Corruption notification
	public static final MessageType corruptionNotification = new MessageType("corruptionNotification") {{
		addField(UID, Long.class);
		addField(URL, String.class);
		addField(FILE_LENGTH, Long.class);
		addField(LAST_MODIFIED, String.class);
		addField(CHUNK_NO, Integer.class);
		addField(IS_HASH, Boolean.class);
	}};
	
	public static final Message createCorruptionNotification(long uid, String url, long fileLength, 
	    String lastModified, int chunkNo, boolean isHash) {
		Message msg = new Message(corruptionNotification);
		msg.set(UID, uid);
		msg.set(URL, url);
		msg.set(FILE_LENGTH, fileLength);
		msg.set(LAST_MODIFIED, lastModified);
		msg.set(CHUNK_NO, chunkNo);
		msg.set(IS_HASH, isHash);
		return msg;
	}

	// New data transmission messages
	public static final MessageType packetTransmit = new MessageType("packetTransmit") {{
		addField(UID, Long.class);
		addField(PACKET_NO, Integer.class);
		addField(SENT, BitArray.class);
		addField(DATA, Buffer.class);
	}};
	
	public static final Message createPacketTransmit(long uid, int packetNo, BitArray sent, Buffer data) {
		Message msg = new Message(packetTransmit);
		msg.set(UID, uid);
		msg.set(PACKET_NO, packetNo);
		msg.set(SENT, sent);
		msg.set(DATA, data);
		return msg;
	}
	
	public static final MessageType allSent = new MessageType("allSent") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createAllSent(long uid) {
		Message msg = new Message(allSent);
		msg.set(UID, uid);
		return msg;
	}
	
	public static final MessageType missingPacketNotification = new MessageType("missingPacketNotification") {{
		addField(UID, Long.class);
		addLinkedListField(MISSING, Integer.class);
	}};
	
	public static final Message createMissingPacketNotification(long uid, LinkedList missing) {
		Message msg = new Message(missingPacketNotification);
		msg.set(UID, uid);
		msg.set(MISSING, missing);
		return msg;
	}
	
	public static final MessageType allReceived = new MessageType("allReceived") {{
		addField(UID, Long.class);
	}};
	public static final Message createAllReceived(long uid) {
		Message msg = new Message(allReceived);
		msg.set(UID, uid);
		return msg;
	}
	
	public static final MessageType sendAborted = new MessageType("sendAborted") {{
		addField(UID, Long.class);
		addField(REASON, String.class);
	}};

	public static final Message createSendAborted(long uid, String reason) {
		Message msg = new Message(sendAborted);
		msg.set(UID, uid);
		msg.set(REASON, reason);
		return msg;
	}

	public static final MessageType testTransferSend = new MessageType("testTransferSend") {{
	    addField(UID, Long.class);
	}};
	
	public static final Message createTestTransferSend(long uid) {
	    Message msg = new Message(testTransferSend);
	    msg.set(UID, uid);
	    return msg;
	}

	public static final MessageType testTransferSendAck = new MessageType("testTransferSendAck") {{
	    addField(UID, Long.class);
	}};
	
	public static final Message createTestTransferSendAck(long uid) {
	    Message msg = new Message(testTransferSendAck);
	    msg.set(UID, uid);
	    return msg;
	}
	
	public static final MessageType testSendCHK = new MessageType("testSendCHK") {{
	    addField(UID, Long.class);
	    addField(FREENET_URI, String.class);
	    addField(CHK_HEADER, Buffer.class);
	}};
	
    public static Message createTestSendCHK(long uid, String uri, Buffer header) {
        Message msg = new Message(testSendCHK);
        msg.set(UID, uid);
        msg.set(FREENET_URI, uri);
        msg.set(CHK_HEADER, header);
        return msg;
    }

    public static final MessageType testRequest = new MessageType("testRequest") {{
        addField(UID, Long.class);
        addField(FREENET_ROUTING_KEY, Key.class);
        addField(HTL, Integer.class);
    }};
    
    public static Message createTestRequest(Key Key, long id, int htl) {
        Message msg = new Message(testRequest);
        msg.set(UID, id);
        msg.set(FREENET_ROUTING_KEY, Key);
        msg.set(HTL, htl);
        return msg;
    }

    public static final MessageType testDataNotFound = new MessageType("testDataNotFound") {{
        addField(UID, Long.class);
    }};
    
    public static Message createTestDataNotFound(long uid) {
        Message msg = new Message(testDataNotFound);
        msg.set(UID, uid);
        return msg;
    }
    
    public static final MessageType testDataReply = new MessageType("testDataReply") {{
        addField(UID, Long.class);
        addField(TEST_CHK_HEADERS, Buffer.class);
    }};
    
    public static final Message createTestDataReply(long uid, byte[] headers) {
        Message msg = new Message(testDataReply);
        msg.set(UID, uid);
        msg.set(TEST_CHK_HEADERS, new Buffer(headers));
        return msg;
    }
    
    public static final MessageType testSendCHKAck = new MessageType("testSendCHKAck") {{
        addField(UID, Long.class);
        addField(FREENET_URI, String.class);
    }};
    public static Message createTestSendCHKAck(long uid, String key) {
        Message msg = new Message(testSendCHKAck);
        msg.set(UID, uid);
        msg.set(FREENET_URI, key);
        return msg;
    }
    
	public static final MessageType testDataReplyAck = new MessageType("testDataReplyAck") {{
	    addField(UID, Long.class);
	}};
	
    public static Message createTestDataReplyAck(long id) {
        Message msg = new Message(testDataReplyAck);
        msg.set(UID, id);
        return msg;
    }

    public static MessageType testDataNotFoundAck = new MessageType("testDataNotFoundAck") {{
        addField(UID, Long.class);
    }};
    public static Message createTestDataNotFoundAck(long id) {
        Message msg = new Message(testDataNotFoundAck);
        msg.set(UID, id);
        return msg;
    }
    
    // Internal only messages
    
    public static MessageType testReceiveCompleted = new MessageType("testReceiveCompleted", true) {{
        addField(UID, Long.class);
        addField(SUCCESS, Boolean.class);
        addField(REASON, String.class);
    }};
    
    public static Message createTestReceiveCompleted(long id, boolean success, String reason) {
        Message msg = new Message(testReceiveCompleted);
        msg.set(UID, id);
        msg.set(SUCCESS, success);
        msg.set(REASON, reason);
        return msg;
    }
    
    public static MessageType testSendCompleted = new MessageType("testSendCompleted", true) {{
        addField(UID, Long.class);
        addField(SUCCESS, Boolean.class);
        addField(REASON, String.class);
    }};

    public static Message createTestSendCompleted(long id, boolean success, String reason) {
        Message msg = new Message(testSendCompleted);
        msg.set(UID, id);
        msg.set(SUCCESS, success);
        msg.set(REASON, reason);
        return msg;
    }

    // FNP messages
    public static MessageType FNPDataRequest = new MessageType("FNPDataRequest") {{
        addField(UID, Long.class);
        addField(HTL, Short.class);
        addField(FREENET_ROUTING_KEY, Key.class);
    }};
    
    public static Message createFNPDataRequest(long id, short htl, Key key) {
        Message msg = new Message(FNPDataRequest);
        msg.set(UID, id);
        msg.set(HTL, htl);
        msg.set(FREENET_ROUTING_KEY, key);
        return msg;
    }
    
    // Hit our tail, try a different node.
    public static MessageType FNPRejectedLoop = new MessageType("FNPRejectLoop") {{
        addField(UID, Long.class);
    }};
    
    public static Message createFNPRejectedLoop(long id) {
        Message msg = new Message(FNPRejectedLoop);
        msg.set(UID, id);
        return msg;
    }
    
    // Too many requests for present capacity. Fail, propagate back
    // to source, and reduce send rate.
    public static MessageType FNPRejectedOverload = new MessageType("FNPRejectOverload") {{
        addField(UID, Long.class);
    }};
    
    public static Message createFNPRejectedOverload(long id) {
        Message msg = new Message(FNPRejectedOverload);
        msg.set(UID, id);
        return msg;
    }

    public static MessageType FNPAccepted = new MessageType("FNPAccepted") {{
        addField(UID, Long.class);
    }};
    
    public static Message createFNPAccepted(long id) {
        Message msg = new Message(FNPAccepted);
        msg.set(UID, id);
        return msg;
    }
    
    public static MessageType FNPDataNotFound = new MessageType("FNPDataNotFound") {{
        addField(UID, Long.class);
    }};
    
    public static Message createFNPDataNotFound(long id) {
        Message msg = new Message(FNPDataNotFound);
        msg.set(UID, id);
        return msg;
    }
    
    public static MessageType FNPDataFound = new MessageType("FNPDataFound") {{
        addField(UID, Long.class);
        addField(BLOCK_HEADERS, ShortBuffer.class);
    }};
    
    public static Message createFNPDataFound(long id, byte[] buf) {
        Message msg = new Message(FNPDataFound);
        msg.set(UID, id);
        msg.set(BLOCK_HEADERS, new ShortBuffer(buf));
        return msg;
    }
    
    public static MessageType FNPRouteNotFound = new MessageType("FNPRouteNotFound") {{
        addField(UID, Long.class);
        addField(HTL, Short.class);
    }};
    
    public static Message createFNPRouteNotFound(long id, short htl) {
        Message msg = new Message(FNPRouteNotFound);
        msg.set(UID, id);
        msg.set(HTL, htl);
        return msg;
    }
    
    public static MessageType FNPInsertRequest = new MessageType("FNPInsertRequest") {{
        addField(UID, Long.class);
        addField(HTL, Short.class);
        addField(FREENET_ROUTING_KEY, Key.class);
    }};
    
    public static Message createFNPInsertRequest(long id, short htl, Key key) {
        Message msg = new Message(FNPInsertRequest);
        msg.set(UID, id);
        msg.set(HTL, htl);
        msg.set(FREENET_ROUTING_KEY, key);
        return msg;
    }
    
    public static MessageType FNPInsertReply = new MessageType("FNPInsertReply") {{
        addField(UID, Long.class);
    }};
    
    public static Message createFNPInsertReply(long id) {
        Message msg = new Message(FNPInsertReply);
        msg.set(UID, id);
        return msg;
    }
    
    public static MessageType FNPDataInsert = new MessageType("FNPDataInsert") {{
        addField(UID, Long.class);
        addField(BLOCK_HEADERS, ShortBuffer.class);
    }};
    
    public static Message createFNPDataInsert(long uid, byte[] headers) {
        Message msg = new Message(FNPDataInsert);
        msg.set(UID, uid);
        msg.set(BLOCK_HEADERS, new ShortBuffer(headers));
        return msg;
    }

    public static MessageType FNPRejectedTimeout = new MessageType("FNPTooSlow") {{
        addField(UID, Long.class);
    }};
    
    public static Message createFNPRejectedTimeout(long uid) {
        Message msg = new Message(FNPRejectedTimeout);
        msg.set(UID, uid);
        return msg;
    }
    
    public static MessageType FNPDataInsertRejected = new MessageType("FNPDataInsertRejected") {{
        addField(UID, Long.class);
        addField(DATA_INSERT_REJECTED_REASON, Short.class);
    }};
    
    public static Message createFNPDataInsertRejected(long uid, short reason) {
        Message msg = new Message(FNPDataInsertRejected);
        msg.set(UID, uid);
        msg.set(DATA_INSERT_REJECTED_REASON, reason);
        return msg;
    }

    public static short DATA_INSERT_REJECTED_VERIFY_FAILED = 1;
    public static short DATA_INSERT_REJECTED_RECEIVE_FAILED = 2;
    
    public static String getDataInsertRejectedReason(short reason) {
        if(reason == DATA_INSERT_REJECTED_VERIFY_FAILED)
            return "Verify failed";
        else if(reason == DATA_INSERT_REJECTED_RECEIVE_FAILED)
            return "Receive failed";
        return "Unknown reason code: "+reason;
    }
    
    public static MessageType FNPPing = new MessageType("FNPPing") {{
        addField(PING_SEQNO, Integer.class);
    }};
    
    public static Message createFNPPing(int seqNo) {
        Message msg = new Message(FNPPing);
        msg.set(PING_SEQNO, seqNo);
        return msg;
    }

    public static MessageType FNPPong = new MessageType("FNPPong") {{
        addField(PING_SEQNO, Integer.class);
    }};
    
    public static Message createFNPPong(int seqNo) {
        Message msg = new Message(FNPPong);
        msg.set(PING_SEQNO, seqNo);
        return msg;
    }
    
    public static MessageType FNPSwapRequest = new MessageType("FNPSwapRequest") {{
        addField(UID, Long.class);
        addField(HASH, ShortBuffer.class);
        addField(HTL, Integer.class);
    }};
    
    public static Message createFNPSwapRequest(long uid, byte[] buf, int htl) {
        Message msg = new Message(FNPSwapRequest);
        msg.set(UID, uid);
        msg.set(HASH, new ShortBuffer(buf));
        msg.set(HTL, htl);
        return msg;
    }
    
    public static MessageType FNPSwapRejected = new MessageType("FNPSwapRejected") {{
        addField(UID, Long.class);
    }};
    
    public static Message createFNPSwapRejected(long uid) {
        Message msg = new Message(FNPSwapRejected);
        msg.set(UID, uid);
        return msg;
    }
    
    public static MessageType FNPSwapReply = new MessageType("FNPSwapReply") {{
        addField(UID, Long.class);
        addField(HASH, ShortBuffer.class);
    }};
    
    public static Message createFNPSwapReply(long uid, byte[] buf) {
        Message msg = new Message(FNPSwapReply);
        msg.set(UID, uid);
        msg.set(HASH, new ShortBuffer(buf));
        return msg;
    }

    public static MessageType FNPSwapCommit = new MessageType("FNPSwapCommit") {{
        addField(UID, Long.class);
        addField(DATA, ShortBuffer.class);
    }};
    
    public static Message createFNPSwapCommit(long uid, byte[] buf) {
        Message msg = new Message(FNPSwapCommit);
        msg.set(UID, uid);
        msg.set(DATA, new ShortBuffer(buf));
        return msg;
    }
    
    public static MessageType FNPSwapComplete = new MessageType("FNPSwapComplete") {{
        addField(UID, Long.class);
        addField(DATA, ShortBuffer.class);
    }};
    
    public static Message createFNPSwapComplete(long uid, byte[] buf) {
        Message msg = new Message(FNPSwapComplete);
        msg.set(UID, uid);
        msg.set(DATA, new ShortBuffer(buf));
        return msg;
    }

    public static MessageType FNPLocChangeNotification = new MessageType("FNPLocationChangeNotification") {{
        addField(LOCATION, Double.class);
    }};
    
    public static Message createFNPLocChangeNotification(double newLoc) {
        Message msg = new Message(FNPLocChangeNotification);
        msg.set(LOCATION, newLoc);
        return msg;
    }

    public static MessageType FNPRoutedPing = new MessageType("FNPRoutedPing") {{
        addRoutedToNodeMessageFields();
        addField(COUNTER, Integer.class);
    }};
    
    public static Message createFNPRoutedPing(long uid, double targetLocation, short htl, int counter) {
        Message msg = new Message(FNPRoutedPing);
        msg.setRoutedToNodeFields(uid, targetLocation, htl);
        msg.set(COUNTER, counter);
        return msg;
    }
    
    public static MessageType FNPRoutedPong = new MessageType("FNPRoutedPong") {{
        addField(UID, Long.class);
        addField(COUNTER, Integer.class);
    }};

    public static Message createFNPRoutedPong(long uid, int counter) {
        Message msg = new Message(FNPRoutedPong);
        msg.set(UID, uid);
        msg.set(COUNTER, counter);
        return msg;
    }
    
    public static MessageType FNPRoutedRejected = new MessageType("FNPRoutedRejected") {{
        addField(UID, Long.class);
        addField(HTL, Short.class);
    }};

    public static Message createFNPRoutedRejected(long uid, short htl) {
        Message msg = new Message(FNPRoutedRejected);
        msg.set(UID, uid);
        msg.set(HTL, htl);
        return msg;
    }
    
	public static void init() { }

}
