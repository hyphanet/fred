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
		addField(UID, Integer.class);
	}};
	
	public static final Message createRejectDueToLoop(int uid) {
		Message msg = new Message(rejectDueToLoop);
		msg.set(UID, uid);
		return msg;
	}
	
	// Assimilation
	public static final MessageType joinRequest = new MessageType("joinRequest") {{
		addField(UID, Integer.class);
		addField(JOINER, Peer.class);
		addField(TTL, Integer.class);
	}};
	
	public static final Message createJoinRequest(int uid, Peer joiner, int ttl) {
		Message msg = new Message(joinRequest);
		msg.set(UID, uid);
		msg.set(JOINER, joiner);
		msg.set(TTL, ttl);
		return msg;
	}
	
	public static final MessageType joinRequestAck = new MessageType("joinRequestAck") {{
		addField(UID, Integer.class);
	}};
	
	public static final Message createJoinRequestAck(int uid) {
		Message msg = new Message(joinRequestAck);
		msg.set(UID, uid);
		return msg;
	}
	
	public static final MessageType joinResponse = new MessageType("joinResponse") {{
		addField(UID, Integer.class);
		addLinkedListField(PEERS, Peer.class);
	}};
	
	public static final Message createJoinResponse(int uid, List peers) {
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
		addField(UID, Integer.class);
		addField(URL, String.class);
		addLinkedListField(FORWARDERS, Peer.class);
		addField(FILE_LENGTH, Long.class);
		addField(LAST_MODIFIED, String.class);
		addField(CHUNK_NO, Integer.class);
		addField(TTL, Integer.class);
	}};
	
	public static final Message createRequestData(int uid, String url, List forwarders, long fileLength, String lastModified, int chunkNo, int ttl) {
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
		addField(UID, Integer.class);
	}};
	
	public static final Message createAcknowledgeRequest(int uid) {
		Message msg = new Message(acknowledgeRequest);
		msg.set(UID, uid);
		return msg;
	}
	
	public static final MessageType requestSuccessful = new MessageType("requestSuccessful") {{
		addField(UID, Integer.class);
		addField(DATA_SOURCE, Peer.class);
		addField(CACHED, Boolean.class);
	}};

	public static final Message createRequestSuccessful(int uid, Peer dataSource, boolean cached) {
		Message msg = new Message(requestSuccessful);
		msg.set(UID, uid);
		msg.set(DATA_SOURCE, dataSource);
		msg.set(CACHED, cached);
		return msg;
	}

	public static final MessageType requestFailed = new MessageType("requestFailed") {{
		addField(UID, Integer.class);
		addField(REASON, Integer.class);
		addField(DESCRIPTION, String.class);
	}};
	
	public static final Message createRequestFailed(int uid, int reason, String description) {
		Message msg = new Message(requestFailed);
		msg.set(UID, uid);
		msg.set(REASON, reason);
		msg.set(DESCRIPTION, description);
		return msg;
	}
	
	// Hash search
	public static final MessageType requestHash = new MessageType("requestHash") {{
		addField(UID, Integer.class);
		addField(URL, String.class);
		addField(FILE_LENGTH, Long.class);
		addField(LAST_MODIFIED, String.class);
		addField(CHUNK_NO, Integer.class);
		addField(TTL, Integer.class);
	}};
	
	public static final Message createRequestHash(int uid, String url, long fileLength, String lastModified, int chunkNo, int ttl) {
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
		addField(UID, Integer.class);
	}};
	
	public static final Message createRequestHashAck(int uid) {
		Message msg = new Message(requestHashAck);
		msg.set(UID, uid);
		return msg;
	}
	
	// Corruption notification
	public static final MessageType corruptionNotification = new MessageType("corruptionNotification") {{
		addField(UID, Integer.class);
		addField(URL, String.class);
		addField(FILE_LENGTH, Long.class);
		addField(LAST_MODIFIED, String.class);
		addField(CHUNK_NO, Integer.class);
		addField(IS_HASH, Boolean.class);
	}};
	
	public static final Message createCorruptionNotification(int uid, String url, long fileLength, 
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
		addField(UID, Integer.class);
		addField(PACKET_NO, Integer.class);
		addField(SENT, BitArray.class);
		addField(DATA, Buffer.class);
	}};
	
	public static final Message createPacketTransmit(int uid, int packetNo, BitArray sent, Buffer data) {
		Message msg = new Message(packetTransmit);
		msg.set(UID, uid);
		msg.set(PACKET_NO, packetNo);
		msg.set(SENT, sent);
		msg.set(DATA, data);
		return msg;
	}
	
	public static final MessageType allSent = new MessageType("allSent") {{
		addField(UID, Integer.class);
	}};
	
	public static final Message createAllSent(int uid) {
		Message msg = new Message(allSent);
		msg.set(UID, uid);
		return msg;
	}
	
	public static final MessageType missingPacketNotification = new MessageType("missingPacketNotification") {{
		addField(UID, Integer.class);
		addLinkedListField(MISSING, Integer.class);
	}};
	
	public static final Message createMissingPacketNotification(int uid, LinkedList missing) {
		Message msg = new Message(missingPacketNotification);
		msg.set(UID, uid);
		msg.set(MISSING, missing);
		return msg;
	}
	
	public static final MessageType allReceived = new MessageType("allReceived") {{
		addField(UID, Integer.class);
	}};
	public static final Message createAllReceived(int uid) {
		Message msg = new Message(allReceived);
		msg.set(UID, uid);
		return msg;
	}
	
	public static final MessageType sendAborted = new MessageType("sendAborted") {{
		addField(UID, Integer.class);
		addField(REASON, String.class);
	}};

	public static final Message createSendAborted(int uid, String reason) {
		Message msg = new Message(sendAborted);
		msg.set(UID, uid);
		msg.set(REASON, reason);
		return msg;
	}

	public static final MessageType testTransferSend = new MessageType("testTransferSend") {{
	    addField(UID, Integer.class);
	}};
	
	public static final Message createTestTransferSend(int uid) {
	    Message msg = new Message(testTransferSend);
	    msg.set(UID, uid);
	    return msg;
	}

	public static final MessageType testTransferSendAck = new MessageType("testTransferSendAck") {{
	    addField(UID, Integer.class);
	}};
	
	public static final Message createTestTransferSendAck(int uid) {
	    Message msg = new Message(testTransferSendAck);
	    msg.set(UID, uid);
	    return msg;
	}
	
	public static void init() { }

}
