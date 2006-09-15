/*
  FrostMessageObject.java / Frost, Freenet
  Copyright (C) 2003-2006  Frost Project <jtcfrost.sourceforge.net>
  Public Domain 2006 VolodyA! V Anarhist <volodya@whengendarmesleeps.org>
  Copyright (C) 2006 Freenet Project

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; either version 2 of
  the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
  General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
*/

package freenet.frost.message;

import java.util.*;

import freenet.client.*;
import freenet.client.ClientMetadata;
import freenet.client.InsertBlock;
import freenet.keys.FreenetURI;
import freenet.support.io.ArrayBucket;
import java.net.MalformedURLException;
import freenet.client.InserterException;


public final class FrostMessage {
    private boolean isValid = false;
    private String invalidReason = null;
    
    private int index = -1;
    private String base = "news";
    private FrostBoard board = null;

    private String content = null;
    private String subject = "(no subject)";
//    private String messageId = null;
    
    private String dateAndTime = null;
    private String timeStr = null;

    private String name;
    
    private String xml = null;
    
    /**
     * Construct a new empty FrostMessageObject
     */
    public FrostMessage() {
    }
    
    public FrostMessage(String base, FrostBoard b, String from, String subject, String content) {
    	setBase(base);
        setBoard(b);
        setName(from);
        if (subject != null && !subject.equals(""))
        	setSubject(subject);
        setContent(content);
    }

/*    public String getDateAndTime() {
        if( dateAndTime == null ) {
            // Build a String of format yyyy.mm.dd hh:mm:ssGMT        
            String date = DateFun.getExtendedDateFromSqlDate(getSqlDate());
            String time = DateFun.getExtendedTimeFromSqlTime(getSqlTime());

            StringBuffer sb = new StringBuffer(29);
            sb.append(date).append(" ").append(time);

            this.dateAndTime = sb.toString();
        }
        return this.dateAndTime;
    }*/
    
    public String getDateAndTime() {
    	if ( dateAndTime == null ) {
    		java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy.MM.dd HH:mm:ssz");
    		dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
   		
    		dateAndTime = dateFormat.format(new Date());
    	}
    	
    	return dateAndTime;
    }
    
    	// 2006.09.06 if pad
    	// 2006.9.6 if not pad
    public String getDateStr(boolean pad) {
    	String dateStr;
    	java.text.SimpleDateFormat dateFormat;
    	
    	if(pad)
    	{
    		dateFormat = new java.text.SimpleDateFormat("yyyy.MM.dd");
    	}
    	else
    	{
    		dateFormat = new java.text.SimpleDateFormat("yyyy.M.d");
    	}
   		
    	dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
   		
   		dateStr = dateFormat.format(new Date());
    		
    	return dateStr;
    }
    
    	// 02:01:53GMT
    public String getTimeStr() {
    	if ( timeStr == null ) {
    		java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("HH:mm:ssz");
    		dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
   		
    		timeStr = dateFormat.format(new Date());
    	}
    	
    	return timeStr;
    }
    
    public FrostBoard getBoard() {
        return board;
    }

    public void setBoard(FrostBoard board) {
        this.board = board;
    }
    
    public String getName() {
    	return name;
    }
    
    public String getId() {
    	Random rnd = new Random((new Date()).getTime());

    	StringBuffer sb = new StringBuffer();
    	
    	for(int i = 0; i<4; i++) {
    		sb.append(Long.toHexString(rnd.nextLong()).toUpperCase());
    	}
    	return sb.toString();
    }
    
    public void setName(String name) {
    	this.name=name;
    }

    	// Dummy
    public boolean containsAttachments() {
        return false;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getInvalidReason() {
        return invalidReason;
    }

    public void setInvalidReason(String invalidReason) {
        this.invalidReason = invalidReason;
    }

    public boolean isValid() {
        return isValid;
    }

    public void setValid(boolean isValid) {
        this.isValid = isValid;
    }

    public String toString() {
        return getSubject();
    }
    public String getContent() {
        return content;
    }
    public String getSubject() {
        return subject;
    }
/*    public String getMessageId() {
        return messageId;
    }
    */
    public void setContent(String content) {
        this.content = content;
    }
    public void setSubject(String subject) {
        this.subject = subject;
    }
/*    public void setMessageId(String s) {
        this.messageId = s;
    }
	*/
    public void setBase(String base) {
    	this.base = base;
    }
    
    	// HACK
    private final String getXml() {
    	if(xml==null)
    	{
    		String messageContent = new StringBuffer()
    			.append("----- ").append(this.getName()).append(" ----- ")
    			.append(this.getDateStr(true)).append(" - ").append(this.getTimeStr()).append(" -----\n\n")
    			.append(this.getContent()).toString();
    		
    		StringBuffer sb = new StringBuffer();
    		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    		sb.append("<FrostMessage>");
    		
    		sb.append("<MessageId><![CDATA[").append(this.getId()).append("]]></MessageId>");
    		
    		//sb.append("<InReplyTo></InReplyTo>");
    		
    		sb.append("<From><![CDATA[").append(this.getName()).append("]]></From>");
    		
    		sb.append("<Subject><![CDATA[").append(this.getSubject()).append("]]></Subject>");
    		
    			// non-padded string
    		sb.append("<Date><![CDATA[").append(this.getDateStr(false)).append("]]></Date>");

    			// format 02:01:53GMT
    		sb.append("<Time><![CDATA[").append(this.getTimeStr()).append("]]></Time>");

    			// might be a good idea to add "----- .Anon. ----- 2006.09.13 - 01:53:20GMT -----"
    		sb.append("<Body><![CDATA[").append(messageContent).append("]]></Body>");

    		sb.append("<Board><![CDATA[").append(this.getBoard().getName()).append("]]></Board>");
    		
    		sb.append("<signatureStatus><![CDATA[OLD]]></signatureStatus>");
    		
    		sb.append("</FrostMessage>");
    		xml = sb.toString();
    	}
    	return xml;
    }
    
    public String getMessageBase() {
    	return base;
    }
    
    /**
     * This method composes the uploading key for the message, given a
     * certain index number
     * @param index index number to use to compose the key
     * @return they composed key
     */
    public FreenetURI composeUploadKey(int index) throws MalformedURLException {
        FreenetURI key;
        if (board.isWriteAccessBoard()) {
            key = new FreenetURI(
            		new StringBuffer()
                    .append(board.getPrivateKey())
                    .append("/")
                    .append(board.getName())
                    .append("/")
                    .append(this.getDateStr(false))
                    .append("-")
                    .append(index)
                    .append(".xml")
                    .toString());
        } else {
            key = new FreenetURI("KSK",
            		new StringBuffer()
                    .append("frost|message|")
                    .append(this.getMessageBase())
                    .append("|")
                    .append(this.getDateStr(false))
                    .append("-")
                    .append(board.getName())
                    .append("-")
                    .append(index)
                    .append(".xml")
                    .toString());
        }
        
        System.err.println("FIN -> Key is  " + key.toString());
        return key;
    }
    
    public final FreenetURI insertMessage(HighLevelSimpleClient client, int innitialIndex) throws InserterException, MalformedURLException
    {
    	boolean keepgoing;
    	FreenetURI key = null;
    	FreenetURI returnKey = null;
    	String type = "text/xml";
    	
    	int moreTries = 50;
    	
    	byte[] data = this.getXml().getBytes();
    	InsertBlock block = null;
    	
    	do // until the message is inserted
    	{
    		key = this.composeUploadKey(innitialIndex);
    		keepgoing = false;

            block = new InsertBlock(new ArrayBucket(data), new ClientMetadata(type), key);

    		// try inserting the message with the key
            try {
            	returnKey = client.insert(block, false); // I don't know what that 'false' is
            }
            catch (InserterException e)
            {
            	System.err.println("FIN -> insert failed with the message" + e.getMessage());
        		if(moreTries--==0) throw e;
        		
        		keepgoing=true;
        		innitialIndex++;
            }
    	} while(keepgoing);
    	
    	return returnKey;
    	
    }
}
