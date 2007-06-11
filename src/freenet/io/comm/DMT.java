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

import java.util.LinkedList;

import freenet.crypt.DSAPublicKey;
import freenet.keys.Key;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.support.BitArray;
import freenet.support.Buffer;
import freenet.support.Fields;
import freenet.support.ShortBuffer;


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
	public static final String NEAREST_LOCATION = "nearestLocation";
	public static final String BEST_LOCATION = "bestLocation";
	public static final String TARGET_LOCATION = "targetLocation";
	public static final String TYPE = "type";
	public static final String PAYLOAD = "payload";
	public static final String COUNTER = "counter";
	public static final String RETURN_LOCATION = "returnLocation";
	public static final String BLOCK_HEADERS = "blockHeaders";
	public static final String DATA_INSERT_REJECTED_REASON = "dataInsertRejectedReason";
	public static final String STREAM_SEQNO = "streamSequenceNumber";
	public static final String IS_LOCAL = "isLocal";
	public static final String ANY_TIMED_OUT = "anyTimedOut";
	public static final String PUBKEY_HASH = "pubkeyHash";
	public static final String NEED_PUB_KEY = "needPubKey";
	public static final String PUBKEY_AS_BYTES = "pubkeyAsBytes";
	public static final String SOURCE_NODENAME = "sourceNodename";
	public static final String TARGET_NODENAME = "targetNodename";
	public static final String NODE_TO_NODE_MESSAGE_TYPE = "nodeToNodeMessageType";
	public static final String NODE_TO_NODE_MESSAGE_TEXT = "nodeToNodeMessageText";
	public static final String NODE_TO_NODE_MESSAGE_DATA = "nodeToNodeMessageData";
	public static final String NODE_UIDS = "nodeUIDs";
	public static final String MY_UID = "myUID";
	public static final String PEER_LOCATIONS = "peerLocations";
	public static final String PEER_UIDS = "peerUIDs";
	public static final String BEST_LOCATIONS_NOT_VISITED = "bestLocationsNotVisited";
	public static final String MAIN_JAR_KEY = "mainJarKey";
	public static final String EXTRA_JAR_KEY = "extraJarKey";
	public static final String REVOCATION_KEY = "revocationKey";
	public static final String HAVE_REVOCATION_KEY = "haveRevocationKey";
	public static final String MAIN_JAR_VERSION = "mainJarVersion";
	public static final String EXTRA_JAR_VERSION = "extJarVersion";
	public static final String REVOCATION_KEY_TIME_LAST_TRIED = "revocationKeyTimeLastTried";
	public static final String REVOCATION_KEY_DNF_COUNT = "revocationKeyDNFCount";
	public static final String REVOCATION_KEY_FILE_LENGTH = "revocationKeyFileLength";
	public static final String MAIN_JAR_FILE_LENGTH = "mainJarFileLength";
	public static final String EXTRA_JAR_FILE_LENGTH = "extraJarFileLength";
	public static final String PING_TIME = "pingTime";
	public static final String BWLIMIT_DELAY_TIME = "bwlimitDelayTime";
	
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

	public static final Message createPong(Message recvPing) {
		if (recvPing.isSet(SEND_TIME)) {
			return createPong(recvPing.getLong(SEND_TIME));
		} else {
			return createPong(500);
		}
	}
	
	public static final Message createPong(long sendTime) {
		Message msg = new Message(pong);
		msg.set(SEND_TIME, sendTime);
		return msg;
	}

	public static final MessageType rejectDueToLoop = new MessageType("rejectDueToLoop") {{ 
		addField(UID, Long.class);
	}};
	
	public static final Message createRejectDueToLoop(long uid) {
		Message msg = new Message(rejectDueToLoop);
		msg.set(UID, uid);
		return msg;
	}
	
	// Assimilation
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
	
	public static int packetTransmitSize(int size, int _packets) {
		return size + 8 /* uid */ + 4 /* packet# */ + 
			BitArray.serializedLength(_packets) + 4 /* Message header */;
	}
	
	public static int bulkPacketTransmitSize(int size) {
		return size + 8 /* uid */ + 4 /* packet# */ + 4 /* Message hader */;
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
		addField(DESCRIPTION, String.class);
		addField(REASON, Integer.class);
	}};

	public static final Message createSendAborted(long uid, int reason, String description) {
		Message msg = new Message(sendAborted);
		msg.set(UID, uid);
		msg.set(REASON, reason);
		msg.set(DESCRIPTION, description);
		return msg;
	}

	public static final MessageType FNPBulkPacketSend = new MessageType("FNPBulkPacketSend") {{
		addField(UID, Long.class);
		addField(PACKET_NO, Integer.class);
		addField(DATA, ShortBuffer.class);
	}};
	
	public static final Message createFNPBulkPacketSend(long uid, int packetNo, ShortBuffer data) {
		Message msg = new Message(FNPBulkPacketSend);
		msg.set(UID, uid);
		msg.set(PACKET_NO, packetNo);
		msg.set(DATA, data);
		return msg;
	}
	
	public static final Message createFNPBulkPacketSend(long uid, int packetNo, byte[] data) {
		return createFNPBulkPacketSend(uid, packetNo, new ShortBuffer(data));
	}
	
	public static final MessageType FNPBulkSendAborted = new MessageType("FNPBulkSendAborted") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createFNPBulkSendAborted(long uid) {
		Message msg = new Message(FNPBulkSendAborted);
		msg.set(UID, uid);
		return msg;
	}
	
	public static final MessageType FNPBulkReceiveAborted = new MessageType("FNPBulkReceiveAborted") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createFNPBulkReceiveAborted(long uid) {
		Message msg = new Message(FNPBulkReceiveAborted);
		msg.set(UID, uid);
		return msg;
	}
	
	public static final MessageType FNPBulkReceivedAll = new MessageType("FNPBulkReceivedAll") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createFNPBulkReceivedAll(long uid) {
		Message msg = new Message(FNPBulkReceivedAll);
		msg.set(UID, uid);
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
	
	public static final Message createTestSendCHK(long uid, String uri, Buffer header) {
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
	
	public static final Message createTestRequest(Key Key, long id, int htl) {
		Message msg = new Message(testRequest);
		msg.set(UID, id);
		msg.set(FREENET_ROUTING_KEY, Key);
		msg.set(HTL, htl);
		return msg;
	}

	public static final MessageType testDataNotFound = new MessageType("testDataNotFound") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createTestDataNotFound(long uid) {
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
	public static final Message createTestSendCHKAck(long uid, String key) {
		Message msg = new Message(testSendCHKAck);
		msg.set(UID, uid);
		msg.set(FREENET_URI, key);
		return msg;
	}
	
	public static final MessageType testDataReplyAck = new MessageType("testDataReplyAck") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createTestDataReplyAck(long id) {
		Message msg = new Message(testDataReplyAck);
		msg.set(UID, id);
		return msg;
	}

	public static final MessageType testDataNotFoundAck = new MessageType("testDataNotFoundAck") {{
		addField(UID, Long.class);
	}};
	public static final Message createTestDataNotFoundAck(long id) {
		Message msg = new Message(testDataNotFoundAck);
		msg.set(UID, id);
		return msg;
	}
	
	// Internal only messages
	
	public static final MessageType testReceiveCompleted = new MessageType("testReceiveCompleted", true) {{
		addField(UID, Long.class);
		addField(SUCCESS, Boolean.class);
		addField(REASON, String.class);
	}};
	
	public static final Message createTestReceiveCompleted(long id, boolean success, String reason) {
		Message msg = new Message(testReceiveCompleted);
		msg.set(UID, id);
		msg.set(SUCCESS, success);
		msg.set(REASON, reason);
		return msg;
	}
	
	public static final MessageType testSendCompleted = new MessageType("testSendCompleted", true) {{
		addField(UID, Long.class);
		addField(SUCCESS, Boolean.class);
		addField(REASON, String.class);
	}};

	public static final Message createTestSendCompleted(long id, boolean success, String reason) {
		Message msg = new Message(testSendCompleted);
		msg.set(UID, id);
		msg.set(SUCCESS, success);
		msg.set(REASON, reason);
		return msg;
	}

	// Node-To-Node Message (generic)
	public static final MessageType nodeToNodeMessage = new MessageType("nodeToNodeMessage", false) {{
		addField(NODE_TO_NODE_MESSAGE_TYPE, Integer.class);
		addField(NODE_TO_NODE_MESSAGE_DATA, ShortBuffer.class);
	}};

	public static final Message createNodeToNodeMessage(int type, byte[] data) {
		Message msg = new Message(nodeToNodeMessage);
		msg.set(NODE_TO_NODE_MESSAGE_TYPE, type);
		msg.set(NODE_TO_NODE_MESSAGE_DATA, new ShortBuffer(data));
		return msg;
	}

	// FNP messages
	public static final MessageType FNPCHKDataRequest = new MessageType("FNPCHKDataRequest") {{
		addField(UID, Long.class);
		addField(HTL, Short.class);
		addField(NEAREST_LOCATION, Double.class);
		addField(FREENET_ROUTING_KEY, NodeCHK.class);
	}};
	
	public static final Message createFNPCHKDataRequest(long id, short htl, NodeCHK key, double nearestLocation) {
		Message msg = new Message(FNPCHKDataRequest);
		msg.set(UID, id);
		msg.set(HTL, htl);
		msg.set(FREENET_ROUTING_KEY, key);
		msg.set(NEAREST_LOCATION, nearestLocation);
		return msg;
	}
	
	public static final MessageType FNPSSKDataRequest = new MessageType("FNPSSKDataRequest") {{
		addField(UID, Long.class);
		addField(HTL, Short.class);
		addField(NEAREST_LOCATION, Double.class);
		addField(FREENET_ROUTING_KEY, NodeSSK.class);
		addField(NEED_PUB_KEY, Boolean.class);
	}};
	
	public static final Message createFNPSSKDataRequest(long id, short htl, NodeSSK key, double nearestLocation, boolean needPubKey) {
		Message msg = new Message(FNPSSKDataRequest);
		msg.set(UID, id);
		msg.set(HTL, htl);
		msg.set(FREENET_ROUTING_KEY, key);
		msg.set(NEAREST_LOCATION, nearestLocation);
		msg.set(NEED_PUB_KEY, needPubKey);
		return msg;
	}
	
	// Hit our tail, try a different node.
	public static final MessageType FNPRejectedLoop = new MessageType("FNPRejectLoop") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createFNPRejectedLoop(long id) {
		Message msg = new Message(FNPRejectedLoop);
		msg.set(UID, id);
		return msg;
	}
	
	// Too many requests for present capacity. Fail, propagate back
	// to source, and reduce send rate.
	public static final MessageType FNPRejectedOverload = new MessageType("FNPRejectOverload") {{
		addField(UID, Long.class);
		addField(IS_LOCAL, Boolean.class);
	}};
	
	public static final Message createFNPRejectedOverload(long id, boolean isLocal) {
		Message msg = new Message(FNPRejectedOverload);
		msg.set(UID, id);
		msg.set(IS_LOCAL, isLocal);
		return msg;
	}
	
	public static final MessageType FNPAccepted = new MessageType("FNPAccepted") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createFNPAccepted(long id) {
		Message msg = new Message(FNPAccepted);
		msg.set(UID, id);
		return msg;
	}
	
	public static final MessageType FNPDataNotFound = new MessageType("FNPDataNotFound") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createFNPDataNotFound(long id) {
		Message msg = new Message(FNPDataNotFound);
		msg.set(UID, id);
		return msg;
	}
	
	public static final MessageType FNPCHKDataFound = new MessageType("FNPCHKDataFound") {{
		addField(UID, Long.class);
		addField(BLOCK_HEADERS, ShortBuffer.class);
	}};
	
	public static final Message createFNPCHKDataFound(long id, byte[] buf) {
		Message msg = new Message(FNPCHKDataFound);
		msg.set(UID, id);
		msg.set(BLOCK_HEADERS, new ShortBuffer(buf));
		return msg;
	}
	
	public static final MessageType FNPRouteNotFound = new MessageType("FNPRouteNotFound") {{
		addField(UID, Long.class);
		addField(HTL, Short.class);
	}};
	
	public static final Message createFNPRouteNotFound(long id, short htl) {
		Message msg = new Message(FNPRouteNotFound);
		msg.set(UID, id);
		msg.set(HTL, htl);
		return msg;
	}
	
	public static final MessageType FNPInsertRequest = new MessageType("FNPInsertRequest") {{
		addField(UID, Long.class);
		addField(HTL, Short.class);
		addField(NEAREST_LOCATION, Double.class);
		addField(FREENET_ROUTING_KEY, Key.class);
	}};
	
	public static final Message createFNPInsertRequest(long id, short htl, Key key, double nearestLoc) {
		Message msg = new Message(FNPInsertRequest);
		msg.set(UID, id);
		msg.set(HTL, htl);
		msg.set(FREENET_ROUTING_KEY, key);
		msg.set(NEAREST_LOCATION, nearestLoc);
		return msg;
	}
	
	public static final MessageType FNPInsertReply = new MessageType("FNPInsertReply") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createFNPInsertReply(long id) {
		Message msg = new Message(FNPInsertReply);
		msg.set(UID, id);
		return msg;
	}
	
	public static final MessageType FNPDataInsert = new MessageType("FNPDataInsert") {{
		addField(UID, Long.class);
		addField(BLOCK_HEADERS, ShortBuffer.class);
	}};
	
	public static final Message createFNPDataInsert(long uid, byte[] headers) {
		Message msg = new Message(FNPDataInsert);
		msg.set(UID, uid);
		msg.set(BLOCK_HEADERS, new ShortBuffer(headers));
		return msg;
	}

	public static final MessageType FNPInsertTransfersCompleted = new MessageType("FNPInsertTransfersCompleted") {{
		addField(UID, Long.class);
		addField(ANY_TIMED_OUT, Boolean.class);
	}};

	public static final Message createFNPInsertTransfersCompleted(long uid, boolean anyTimedOut) {
		Message msg = new Message(FNPInsertTransfersCompleted);
		msg.set(UID, uid);
		msg.set(ANY_TIMED_OUT, anyTimedOut);
		return msg;
	}
	
	public static final MessageType FNPRejectedTimeout = new MessageType("FNPTooSlow") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createFNPRejectedTimeout(long uid) {
		Message msg = new Message(FNPRejectedTimeout);
		msg.set(UID, uid);
		return msg;
	}
	
	public static final MessageType FNPDataInsertRejected = new MessageType("FNPDataInsertRejected") {{
		addField(UID, Long.class);
		addField(DATA_INSERT_REJECTED_REASON, Short.class);
	}};
	
	public static final Message createFNPDataInsertRejected(long uid, short reason) {
		Message msg = new Message(FNPDataInsertRejected);
		msg.set(UID, uid);
		msg.set(DATA_INSERT_REJECTED_REASON, reason);
		return msg;
	}

	public static final short DATA_INSERT_REJECTED_VERIFY_FAILED = 1;
	public static final short DATA_INSERT_REJECTED_RECEIVE_FAILED = 2;
	public static final short DATA_INSERT_REJECTED_SSK_ERROR = 3;
	
	public static final String getDataInsertRejectedReason(short reason) {
		if(reason == DATA_INSERT_REJECTED_VERIFY_FAILED)
			return "Verify failed";
		else if(reason == DATA_INSERT_REJECTED_RECEIVE_FAILED)
			return "Receive failed";
		else if(reason == DATA_INSERT_REJECTED_SSK_ERROR)
			return "SSK error";
		return "Unknown reason code: "+reason;
	}

	public static final MessageType FNPSSKInsertRequest = new MessageType("FNPSSKInsertRequest") {{
		addField(UID, Long.class);
		addField(HTL, Short.class);
		addField(FREENET_ROUTING_KEY, NodeSSK.class);
		addField(NEAREST_LOCATION, Double.class);
		addField(BLOCK_HEADERS, ShortBuffer.class);
		addField(PUBKEY_HASH, ShortBuffer.class);
		addField(DATA, ShortBuffer.class);
	}};
	
	public static Message createFNPSSKInsertRequest(long uid, short htl, NodeSSK myKey, double closestLocation, byte[] headers, byte[] data, byte[] pubKeyHash) {
		Message msg = new Message(FNPSSKInsertRequest);
		msg.set(UID, uid);
		msg.set(HTL, htl);
		msg.set(FREENET_ROUTING_KEY, myKey);
		msg.set(NEAREST_LOCATION, closestLocation);
		msg.set(BLOCK_HEADERS, new ShortBuffer(headers));
		msg.set(PUBKEY_HASH, new ShortBuffer(pubKeyHash));
		msg.set(DATA, new ShortBuffer(data));
		return msg;
	}

	public static final MessageType FNPSSKDataFound = new MessageType("FNPSSKDataFound") {{
		addField(UID, Long.class);
		addField(BLOCK_HEADERS, ShortBuffer.class);
		addField(DATA, ShortBuffer.class);
	}};
	
	public static Message createFNPSSKDataFound(long uid, byte[] headers, byte[] data) {
		Message msg = new Message(FNPSSKDataFound);
		msg.set(UID, uid);
		msg.set(BLOCK_HEADERS, new ShortBuffer(headers));
		msg.set(DATA, new ShortBuffer(data));
		return msg;
	}
	
	public static MessageType FNPSSKAccepted = new MessageType("FNPSSKAccepted") {{
		addField(UID, Long.class);
		addField(NEED_PUB_KEY, Boolean.class);
	}};
	
	public static final Message createFNPSSKAccepted(long uid, boolean needPubKey) {
		Message msg = new Message(FNPSSKAccepted);
		msg.set(UID, uid);
		msg.set(NEED_PUB_KEY, needPubKey);
		return msg;
	}
	
	public static MessageType FNPSSKPubKey = new MessageType("FNPSSKPubKey") {{
		addField(UID, Long.class);
		addField(PUBKEY_AS_BYTES, ShortBuffer.class);
	}};
	
	public static Message createFNPSSKPubKey(long uid, DSAPublicKey pubkey) {
		Message msg = new Message(FNPSSKPubKey);
		msg.set(UID, uid);
		msg.set(PUBKEY_AS_BYTES, new ShortBuffer(pubkey.asPaddedBytes()));
		return msg;
	}
	
	public static MessageType FNPSSKPubKeyAccepted = new MessageType("FNPSSKPubKeyAccepted") {{
		addField(UID, Long.class);
	}};
	
	public static Message createFNPSSKPubKeyAccepted(long uid) {
		Message msg = new Message(FNPSSKPubKeyAccepted);
		msg.set(UID, uid);
		return msg;
	}
	
	public static final MessageType FNPPing = new MessageType("FNPPing") {{
		addField(PING_SEQNO, Integer.class);
	}};
	
	public static final Message createFNPPing(int seqNo) {
		Message msg = new Message(FNPPing);
		msg.set(PING_SEQNO, seqNo);
		return msg;
	}

	public static final MessageType FNPLinkPing = new MessageType("FNPLinkPing") {{
		addField(PING_SEQNO, Long.class);
	}};
	
	public static final Message createFNPLinkPing(long seqNo) {
		Message msg = new Message(FNPLinkPing);
		msg.set(PING_SEQNO, seqNo);
		return msg;
	}
	
	public static final MessageType FNPLinkPong = new MessageType("FNPLinkPong") {{
		addField(PING_SEQNO, Long.class);
	}};
	
	public static final Message createFNPLinkPong(long seqNo) {
		Message msg = new Message(FNPLinkPong);
		msg.set(PING_SEQNO, seqNo);
		return msg;
	}
	
	public static final MessageType FNPPong = new MessageType("FNPPong") {{
		addField(PING_SEQNO, Integer.class);
	}};
	
	public static final Message createFNPPong(int seqNo) {
		Message msg = new Message(FNPPong);
		msg.set(PING_SEQNO, seqNo);
		return msg;
	}
	
	public static final MessageType FNPProbeRequest = new MessageType("FNPProbeRequest") {{
		addField(UID, Long.class);
		addField(TARGET_LOCATION, Double.class);
		addField(NEAREST_LOCATION, Double.class);
		addField(BEST_LOCATION, Double.class);
		addField(HTL, Short.class);
		addField(COUNTER, Short.class);
	}};
	
	public static final Message createFNPProbeRequest(long uid, double target, double nearest, 
			double best, short htl, short counter) {
		Message msg = new Message(FNPProbeRequest);
		msg.set(UID, uid);
		msg.set(TARGET_LOCATION, target);
		msg.set(NEAREST_LOCATION, nearest);
		msg.set(BEST_LOCATION, best);
		msg.set(HTL, htl);
		msg.set(COUNTER, counter);
		return msg;
	}

	public static final MessageType FNPProbeTrace = new MessageType("FNPProbeTrace") {{
		addField(UID, Long.class);
		addField(TARGET_LOCATION, Double.class);
		addField(NEAREST_LOCATION, Double.class);
		addField(BEST_LOCATION, Double.class);
		addField(HTL, Short.class);
		addField(COUNTER, Short.class);
		addField(LOCATION, Double.class);
		addField(MY_UID, Long.class);
		addField(PEER_LOCATIONS, ShortBuffer.class);
		addField(PEER_UIDS, ShortBuffer.class);
	}};
	
	public static Message createFNPProbeTrace(long uid, double target, double nearest, double best, short htl, short counter, double myLoc, long swapIdentifier, double[] peerLocs, long[] peerUIDs) {
		Message msg = new Message(FNPProbeTrace);
		msg.set(UID, uid);
		msg.set(TARGET_LOCATION, target);
		msg.set(NEAREST_LOCATION, nearest);
		msg.set(BEST_LOCATION, best);
		msg.set(HTL, htl);
		msg.set(COUNTER, counter);
		msg.set(LOCATION, myLoc);
		msg.set(MY_UID, swapIdentifier);
		msg.set(PEER_LOCATIONS, new ShortBuffer(Fields.doublesToBytes(peerLocs)));
		msg.set(PEER_UIDS, new ShortBuffer(Fields.longsToBytes(peerUIDs)));
		return msg;
	}

	public static final MessageType FNPProbeReply = new MessageType("FNPProbeReply") {{
		addField(UID, Long.class);
		addField(TARGET_LOCATION, Double.class);
		addField(NEAREST_LOCATION, Double.class);
		addField(BEST_LOCATION, Double.class);
		addField(COUNTER, Short.class);
	}};
	
	public static final Message createFNPProbeReply(long uid, double target, double nearest, 
			double best, short counter) {
		Message msg = new Message(FNPProbeReply);
		msg.set(UID, uid);
		msg.set(TARGET_LOCATION, target);
		msg.set(NEAREST_LOCATION, nearest);
		msg.set(BEST_LOCATION, best);
		msg.set(COUNTER, counter);
		return msg;
	}
	
	public static final MessageType FNPProbeRejected = new MessageType("FNPProbeRejected") {{
		addField(UID, Long.class);
		addField(TARGET_LOCATION, Double.class);
		addField(NEAREST_LOCATION, Double.class);
		addField(BEST_LOCATION, Double.class);
		addField(HTL, Short.class);
		addField(COUNTER, Short.class);
		addField(REASON, Short.class);
	}};
	
	public static final Message createFNPProbeRejected(long uid, double target, double nearest, 
			double best, short counter, short htl, short reason) {
		Message msg = new Message(FNPProbeRejected);
		msg.set(UID, uid);
		msg.set(TARGET_LOCATION, target);
		msg.set(NEAREST_LOCATION, nearest);
		msg.set(BEST_LOCATION, best);
		msg.set(HTL, htl);
		msg.set(COUNTER, counter);
		msg.set(REASON, reason);
		return msg;
	}

	static public final short PROBE_REJECTED_LOOP = 1;
	static public final short PROBE_REJECTED_RNF = 2;
	static public final short PROBE_REJECTED_OVERLOAD = 3;
	
	public static final MessageType FNPSwapRequest = new MessageType("FNPSwapRequest") {{
		addField(UID, Long.class);
		addField(HASH, ShortBuffer.class);
		addField(HTL, Integer.class);
	}};
	
	public static final Message createFNPSwapRequest(long uid, byte[] buf, int htl) {
		Message msg = new Message(FNPSwapRequest);
		msg.set(UID, uid);
		msg.set(HASH, new ShortBuffer(buf));
		msg.set(HTL, htl);
		return msg;
	}
	
	public static final MessageType FNPSwapRejected = new MessageType("FNPSwapRejected") {{
		addField(UID, Long.class);
	}};
	
	public static final Message createFNPSwapRejected(long uid) {
		Message msg = new Message(FNPSwapRejected);
		msg.set(UID, uid);
		return msg;
	}
	
	public static final MessageType FNPSwapReply = new MessageType("FNPSwapReply") {{
		addField(UID, Long.class);
		addField(HASH, ShortBuffer.class);
	}};
	
	public static final Message createFNPSwapReply(long uid, byte[] buf) {
		Message msg = new Message(FNPSwapReply);
		msg.set(UID, uid);
		msg.set(HASH, new ShortBuffer(buf));
		return msg;
	}

	public static final MessageType FNPSwapCommit = new MessageType("FNPSwapCommit") {{
		addField(UID, Long.class);
		addField(DATA, ShortBuffer.class);
	}};
	
	public static final Message createFNPSwapCommit(long uid, byte[] buf) {
		Message msg = new Message(FNPSwapCommit);
		msg.set(UID, uid);
		msg.set(DATA, new ShortBuffer(buf));
		return msg;
	}
	
	public static final MessageType FNPSwapComplete = new MessageType("FNPSwapComplete") {{
		addField(UID, Long.class);
		addField(DATA, ShortBuffer.class);
	}};
	
	public static final Message createFNPSwapComplete(long uid, byte[] buf) {
		Message msg = new Message(FNPSwapComplete);
		msg.set(UID, uid);
		msg.set(DATA, new ShortBuffer(buf));
		return msg;
	}

	public static final MessageType FNPLocChangeNotification = new MessageType("FNPLocationChangeNotification") {{
		addField(LOCATION, Double.class);
	}};
	
	public static final Message createFNPLocChangeNotification(double newLoc) {
		Message msg = new Message(FNPLocChangeNotification);
		msg.set(LOCATION, newLoc);
		return msg;
	}

	public static final MessageType FNPRoutedPing = new MessageType("FNPRoutedPing") {{
		addRoutedToNodeMessageFields();
		addField(COUNTER, Integer.class);
	}};
	
	public static final Message createFNPRoutedPing(long uid, double targetLocation, short htl, int counter) {
		Message msg = new Message(FNPRoutedPing);
		msg.setRoutedToNodeFields(uid, targetLocation, htl);
		msg.set(COUNTER, counter);
		return msg;
	}
	
	public static final MessageType FNPRoutedPong = new MessageType("FNPRoutedPong") {{
		addField(UID, Long.class);
		addField(COUNTER, Integer.class);
	}};

	public static final Message createFNPRoutedPong(long uid, int counter) {
		Message msg = new Message(FNPRoutedPong);
		msg.set(UID, uid);
		msg.set(COUNTER, counter);
		return msg;
	}
	
	public static final MessageType FNPRoutedRejected = new MessageType("FNPRoutedRejected") {{
		addField(UID, Long.class);
		addField(HTL, Short.class);
	}};

	public static final Message createFNPRoutedRejected(long uid, short htl) {
		Message msg = new Message(FNPRoutedRejected);
		msg.set(UID, uid);
		msg.set(HTL, htl);
		return msg;
	}

	public static final MessageType FNPDetectedIPAddress = new MessageType("FNPDetectedIPAddress") {{
		addField(EXTERNAL_ADDRESS, Peer.class);
	}};
	
	public static final Message createFNPDetectedIPAddress(Peer peer) {
		Message msg = new Message(FNPDetectedIPAddress);
		msg.set(EXTERNAL_ADDRESS, peer);
		return msg;
	}

	public static final MessageType FNPVoid = new MessageType("FNPVoid") {{
	}};
	
	public static final Message createFNPVoid() {
		Message msg = new Message(FNPVoid);
		return msg;
	}
	
	// Update over mandatory. Not strictly part of FNP. Only goes between nodes at the link
	// level, and will be sent, and parsed, even if the node is out of date. Should be stable 
	// long-term.
	
	// Sent on connect
	public static final MessageType UOMAnnounce = new MessageType("UOMAnnounce") {{
		addField(MAIN_JAR_KEY, String.class);
		addField(EXTRA_JAR_KEY, String.class);
		addField(REVOCATION_KEY, String.class);
		addField(HAVE_REVOCATION_KEY, Boolean.class);
		addField(MAIN_JAR_VERSION, Long.class);
		addField(EXTRA_JAR_VERSION, Long.class);
		// Last time (ms ago) we had 3 DNFs in a row on the revocation checker.
		addField(REVOCATION_KEY_TIME_LAST_TRIED, Long.class);
		// Number of DNFs so far this time.
		addField(REVOCATION_KEY_DNF_COUNT, Integer.class);
		// For convenience, may change
		addField(REVOCATION_KEY_FILE_LENGTH, Long.class);
		addField(MAIN_JAR_FILE_LENGTH, Long.class);
		addField(EXTRA_JAR_FILE_LENGTH, Long.class);
		addField(PING_TIME, Integer.class);
		addField(BWLIMIT_DELAY_TIME, Integer.class);
	}};

	public static final Message createUOMAnnounce(String mainKey, String extraKey, String revocationKey,
			boolean haveRevocation, long mainJarVersion, long extraJarVersion, long timeLastTriedRevocationFetch,
			int revocationDNFCount, long revocationKeyLength, long mainJarLength, long extraJarLength, int pingTime, int bwlimitDelayTime) {
		Message msg = new Message(UOMAnnounce);
		
		msg.set(MAIN_JAR_KEY, mainKey);
		msg.set(EXTRA_JAR_KEY, extraKey);
		msg.set(REVOCATION_KEY, revocationKey);
		msg.set(HAVE_REVOCATION_KEY, haveRevocation);
		msg.set(MAIN_JAR_VERSION, mainJarVersion);
		msg.set(EXTRA_JAR_VERSION, extraJarVersion);
		msg.set(REVOCATION_KEY_TIME_LAST_TRIED, timeLastTriedRevocationFetch);
		msg.set(REVOCATION_KEY_DNF_COUNT, revocationDNFCount);
		msg.set(REVOCATION_KEY_FILE_LENGTH, revocationKeyLength);
		msg.set(MAIN_JAR_FILE_LENGTH, mainJarLength);
		msg.set(EXTRA_JAR_FILE_LENGTH, extraJarLength);
		msg.set(PING_TIME, pingTime);
		msg.set(BWLIMIT_DELAY_TIME, bwlimitDelayTime);
		
		return msg;
	}
	
	public static final MessageType UOMRequestRevocation = new MessageType("UOMRequestRevocation") {{
		
	}};
	
	public static final Message createUOMRequestRevocation() {
		return new Message(UOMRequestRevocation);
	}
	
	public static final MessageType UOMRequestMain = new MessageType("UOMRequestMain") {{
		
	}};
	
	public static final Message createUOMRequestMain() {
		return new Message(UOMRequestMain);
	}
	
	public static final MessageType UOMRequestExtra = new MessageType("UOMRequestExtra") {{
		
	}};
	
	public static final Message createUOMRequestExtra() {
		return new Message(UOMRequestExtra);
	}
	
	public static final MessageType UOMSendingRevocation = new MessageType("UOMSendingRevocation") {{
		addField(UID, Long.class);
		// Probably excessive, but lengths are always long's, and wasting a few bytes here
		// doesn't matter in the least, as it's very rarely called.
		addField(FILE_LENGTH, Long.class);
	}};
	
	public static final Message createUOMSendingRevocation(long uid, long length) {
		Message msg = new Message(UOMSendingRevocation);
		msg.set(UID, uid);
		msg.set(FILE_LENGTH, length);
		return msg;
	}
	
	public static final MessageType UOMSendingMain = new MessageType("UOMSendingMain") {{
		addField(UID, Long.class);
		addField(FILE_LENGTH, Long.class);
	}};
	
	public static final Message createUOMSendingMain(long uid, long length) {
		Message msg = new Message(UOMSendingMain);
		msg.set(UID, uid);
		msg.set(FILE_LENGTH, length);
		return msg;
	}
	
	public static final MessageType UOMSendingExtra = new MessageType("UOMSendingExtra") {{
		addField(UID, Long.class);
		addField(FILE_LENGTH, Long.class);
	}};
	
	public static final Message createUOMSendingExtra(long uid, long length) {
		Message msg = new Message(UOMSendingExtra);
		msg.set(UID, uid);
		msg.set(FILE_LENGTH, length);
		return msg;
	}
	
	// Secondary messages (debug messages attached to primary messages)
	
	public static final MessageType FNPSwapNodeUIDs = new MessageType("FNPSwapNodeUIDs") {{
		addField(NODE_UIDS, ShortBuffer.class);
	}};
	
	public static final Message createFNPSwapLocations(byte[] uids) {
		Message msg = new Message(FNPSwapNodeUIDs);
		msg.set(NODE_UIDS, new ShortBuffer(uids));
		return msg;
	}
	
	public static final Message createFNPSwapLocations(long[] uids) {
		Message msg = new Message(FNPSwapNodeUIDs);
		msg.set(NODE_UIDS, new ShortBuffer(Fields.longsToBytes(uids)));
		return msg;
	}
	
	// More permanent secondary messages (should perhaps be replaced by new main messages when stable)
	
	public static final MessageType FNPBestRoutesNotTaken = new MessageType("FNPBestRoutesNotTaken") {{
		// Maybe this should be some sort of typed array?
		// It's just a bunch of double's anyway.
		addField(BEST_LOCATIONS_NOT_VISITED, ShortBuffer.class);
	}};
	
	public static final Message createFNPBestRoutesNotTaken(byte[] locs) {
		Message msg = new Message(FNPBestRoutesNotTaken);
		msg.set(BEST_LOCATIONS_NOT_VISITED, new ShortBuffer(locs));
		return msg;
	}
	
	public static final Message createFNPBestRoutesNotTaken(double[] locs) {
		return createFNPBestRoutesNotTaken(Fields.doublesToBytes(locs));
	}
	
	public static Message createFNPBestRoutesNotTaken(Double[] doubles) {
		double[] locs = new double[doubles.length];
		for(int i=0;i<locs.length;i++) locs[i] = doubles[i].doubleValue();
		return createFNPBestRoutesNotTaken(locs);
	}

	public static void init() { }

}
