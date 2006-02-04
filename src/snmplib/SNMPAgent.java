package snmplib;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Date;
import java.util.Iterator;
import java.util.TreeMap;

public class SNMPAgent implements Runnable {
	private int port = 4445;

	/**
	 * @param args 
	 */
	public static void main(String[] args) throws IOException {
		SNMPAgent.getSNMPAgent().addFetcher(new DataConstantInt("1.1.1", 10));
		SNMPAgent.getSNMPAgent().addFetcher(new DataConstantInt("1.1.2", 20));
		SNMPAgent.getSNMPAgent().addFetcher(new DataConstantInt("1.1.3", 30));
		SNMPAgent.getSNMPAgent().addFetcher(new DataConstantInt("1.1.4", 40));
		SNMPAgent.getSNMPAgent().addFetcher(new DataConstantInt("1.1.5", 50));
		SNMPAgent.getSNMPAgent().addFetcher(new DataConstantString("1.1.0", "Step by 10"));
		SNMPAgent.getSNMPAgent().addFetcher(new DataConstantString("1.2", "Nothing here"));
	}
	
    protected DatagramSocket socket = null;
    protected BufferedReader in = null;
    protected boolean moreQuotes = true;
    private TreeMap alldata;
    private static SNMPAgent _SNMPAgent = null;

    public static void setSNMPPort(int port) {
    	ensureCreated();
    	_SNMPAgent.port = port;
    	restartSNMPAgent();
    }
    
    public static void restartSNMPAgent() {
    	ensureCreated();
    	_SNMPAgent.stopRunning();
    	new Thread(_SNMPAgent, "SNMP-Agent").start();
    }
    
    public static SNMPAgent getSNMPAgent() {
    	ensureCreated();
    	return _SNMPAgent;
    }
    
    private static void ensureCreated() {
    	if (_SNMPAgent == null)
    		_SNMPAgent = new SNMPAgent();
    }
    
    private SNMPAgent() {
    	alldata = new TreeMap();
    	//alldata.put("99.99", null);
    	addFetcher(new DataConstantInt("99.99.99.99", 0));
    }
    
    public void addFetcher(DataFetcher df) {
    	//DataHandler dh = new DataHandler(df);
    	//alldata.put(dh.getStringOID(), dh);
    	alldata.put(df.getSNMPOID().replaceAll("^\\.1\\.3\\.",""), df);
    	//System.err.println("sAdded: " + df.getSNMPOID() + "as" + df.getSNMPOID().replaceAll("^\\.1\\.3\\.",""));
    }
    
    public void addFetcher(MultiplexedDataFetcher df) {
    	String oid;
    	for (int i = 0 ; (oid = df.getSNMPOID(i)) != null ; i++) {
    		alldata.put(oid.replaceAll("^\\.1\\.3\\.",""), df);
    	//	System.err.println("mAdded: " + oid + " as: " + oid.replaceAll("^\\.1\\.3\\.",""));
    	}
    }

    public void removeFetcher(String OID) {
    	alldata.remove(((OID.startsWith("\\."))?"":".") + OID);
    }
    
    public void stopRunning() {
    	try {
    		socket.close();
    	} catch (Throwable e) {
    		// prpbably since not running...
    	}
    }

    public void run() {
    	try {
    		socket = new DatagramSocket(port, InetAddress.getByName("localhost"));
    	} catch (IOException e) {
    		e.printStackTrace();
    		return ;
    	}
    	// make smaller.... 0484 enough?

        while (socket.isBound()) {
        	byte[] buf = new byte[65536];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            
            try {
                socket.receive(packet);

                RequestContainer rc = new RequestContainer();
                int replylength = 0;
                //DataHandler dh = null;

                //if (rc != null)
                //	throw new BadFormatException("asdfa");
                
                BERDecoder question = parseRequestStart(buf, rc);
                
                
                BEREncoder reply = replyStart(rc);

                
                while (question.sequenceHasMore()) {
                	question.startSequence();
                	rc.lOID = question.fetchOID();
                	rc.OID = (rc.lOID.length == 0)?".":"";
                	for (int i = 0; i < rc.lOID.length ; i++)
                		rc.OID += (i==0?"":".") + rc.lOID[i];
                	//System.err.println("Doing: " + rc.OID);
                	question.fetchNull();
                	question.endSequence();
                	replyAddOID(reply, rc);
                }
                replylength = replyEnd(reply, buf);
                //rc.pdutype == RequestContainer.PDU_GET_NEXT
                
                // send the response to the client at "address" and "port"
                InetAddress address = packet.getAddress();
                int port = packet.getPort();
                packet = new DatagramPacket(buf, replylength, address, port);
                socket.send(packet);
                
            } catch (IOException e) {
                e.printStackTrace();
                break;
            } catch (BadFormatException e) {
            	System.err.println("Datapacket length: " + packet.getLength());
            	for (int i = 0 ; i < packet.getLength() ; i++) {
            		String num = "000" + Integer.toHexString(buf[i]);
            		num = num.substring(num.length()-2);
            		System.err.print("0x" + num + " ");
            		if ((i+1)%8 == 0)
            			System.err.print("  ");
            		if ((i+1)%16 == 0)
            			System.err.println();
            	}
            	System.err.println();
            	e.printStackTrace();
            	//System.err.println(e.toString());
            } catch (ArrayIndexOutOfBoundsException e) {
            	e.printStackTrace();
            	// not much to do.. ignore the request and it'll time out
            }
        }
        socket.close();
    }
    
    private String getAccualOID(String oid, boolean thisval) {
        boolean keyfound = false;
        Iterator it = alldata.keySet().iterator();
        String key = "";
        while (it.hasNext() && !keyfound) {
        	key = (String)it.next();
        	if (key.startsWith(oid))
        		keyfound = true;
        }
        
        
        if (it.hasNext() && !thisval) {
        	key = key.equals(oid)?(String)it.next():key;
        }
        
        return key;
    }
    
    private Object getResultFromOID(String oid, boolean thisval) {
        /*boolean keyfound = false;
        Iterator it = alldata.keySet().iterator();
        String key = "";
        while (it.hasNext() && !keyfound) {
        	key = (String)it.next();
        	if (key.startsWith(oid))
        		keyfound = true;
        }
        */
        // keyfound /\ ( (equal -> hasnext) V (rc.pdutype == rc.PDU_GET_THIS))
        //System.err.println("("+keyfound+" && (!"+key.equals(rc.OID)+" || "+it.hasNext()+"))");
        /*if (keyfound && (
        		(!key.equals(rc.OID) || it.hasNext())) ||
        		(rc.pdutype == RequestContainer.PDU_GET_THIS) ) {
        	*/
        Object data = null;
        /*if (keyfound && (
        		(!key.equals(oid) || it.hasNext())) ) {
        	if (it.hasNext() && !thisval)
        		key = key.equals(oid)?(String)it.next():key;
        	*/
        	Object df = alldata.get(oid);
        	
        	if (df instanceof DataFetcher) {
        		data = ((DataFetcher)df).getSNMPData();
        	} else if (df instanceof MultiplexedDataFetcher) {
        		data = ((MultiplexedDataFetcher)df).getSNMPData(oid);
        		if (data == null) 
        			if (!oid.startsWith(".1.3."))
        				data = ((MultiplexedDataFetcher)df).getSNMPData(".1.3."+oid);
        			else
        				data = ((MultiplexedDataFetcher)df).getSNMPData(oid.substring(5));
        		
        	} else
        		data = null; //new Integer(0);
        	
        	//rc.lOID = splitToLong(key);
        	//replylength = makeIntReply(buf, rc, data);
        //} else {
        	/*
        	if (rc.lOID.length > 0)
        		rc.lOID[0] = 100;
        	else {
        		rc.lOID = new long[1];
        		rc.lOID[0] = 0;
        	}
        	data = new Integer(1);
        	*/
        //	data = null;
        	//replylength = makeIntReply(buf, rc, new Integer(1));
        //}
        if (data == null)
        	debug("DNF@"+oid);
        return data;
    }
    
    private void debug(String s) {
    	System.err.println("SNMP-Agent " + (new Date()) + ": " + s);
    }
    
    private BEREncoder replyStart(RequestContainer rc) /* throws SnmpTooBigException */ {
    	int replyLength = 0;
    	BEREncoder be = new BEREncoder();
    	be.startSequence(); // whole pkg
    	be.putInteger(0); // version
    	be.putOctetString(rc.community); // community
    	be.startSequence((byte)0xa2); // Response
    	be.putInteger(rc.requestID); // RID
    	be.putInteger(0); // err
    	be.putInteger(0); // err
    	be.startSequence(); // OID:s and their values
    	
    	return be;
    }
    
    private void replyAddOID(BEREncoder be, RequestContainer rc) /* throws SnmpTooBigException */ {
    	String aOID = getAccualOID(rc.OID, rc.pdutype == RequestContainer.PDU_GET_THIS);
    	Object data = getResultFromOID(aOID, rc.pdutype == RequestContainer.PDU_GET_THIS);
    	be.startSequence(); // value
    	be.putOID(splitToLong(aOID)); // oid
    	//System.err.println("Will reply with OID: " + rc.OID + " -> " + aOID);
    	if (data instanceof Integer)
    		be.putInteger(((Integer)data).intValue());
    	else if (data instanceof Long)
    		be.putInteger(((Long)data).longValue());
    	else if (data instanceof SNMPTimeTicks)
    		be.putTimeticks(((SNMPTimeTicks)data).timeValue());
    	else if (data instanceof String) {
    		char[] charr = ((String)data).toCharArray();
    		byte[] byarr = new byte[charr.length];
    		for (int i = 0 ; i < charr.length ; i++)
    			byarr[i] = (byte)charr[i];
    		be.putOctetString(byarr);
    	}
    	be.endSequence();
    }
    
    private int replyEnd(BEREncoder be, byte[] buf) /* throws SnmpTooBigException */ {
    	return be.toBytes(buf);
    }

    
    // http://www.rane.com/note161.html
    private BERDecoder parseRequestStart(byte buf[], RequestContainer rc) throws BadFormatException {
    	int tmpint;
    	
    	BERDecoder bd = new BERDecoder(buf);
    	bd.startSequence();
    	if ((tmpint = bd.fetchInt()) != 0)
    		throw new BadFormatException("Wrong version, expected 0, got "
    				+ tmpint);
    	
    	rc.community = bd.fetchOctetString();
    	if (! rc.setPDU(bd.peekRaw()))
    		throw new BadFormatException("Unknown PDU");
    	bd.startSequence(bd.peekRaw());
    	rc.requestID = bd.fetchInt();
    	
    	// TODO: care about errors eventually?
    	bd.fetchInt();
    	bd.fetchInt();
    	
    	bd.startSequence();
    	/*
    	
    	bd.startSequence();
    	rc.lOID = bd.fetchOID();
    	rc.OID = (rc.lOID.length == 0)?".":"";
    	for (int i = 0; i < rc.lOID.length ; i++)
    		rc.OID += (i==0?"":".") + rc.lOID[i];
    	*/
    	return bd;
    }
    
    private long[] splitToLong(String list) {
    	if (!list.startsWith(".1.3."))
    		list = ".1.3." + list;
    	list = list.substring(1);
    	String nums[] = list.split("\\.");
    	long ret[] = new long[nums.length];
    	for(int i = 0; i < ret.length ; i++) {
    		ret[i] = Long.parseLong(nums[i]);
    		//System.err.print("," + Long.parseLong(nums[i]));
    	}
    	// System.err.println();
    	return ret;
    }
    /*
	private class DataHandler {
    	//public Integer data;
    	public long lOID[] = null;
    	DataFetcher df;
    	
    	public DataHandler(DataFetcher df) {
    		lOID = splitToLong(df.getSNMPOID());
    		this.df = df;
    	}
    	
        private long[] splitToLong(String list) {
        	String nums[] = list.split("\\.");
        	long ret[] = new long[nums.length];
        	for(int i = 0; i < ret.length ; i++)
        		ret[i] = Long.parseLong(nums[i]);
        	return ret;
        }

        public Object getData() {
        	return df.getSNMPData(); 
        }
    	
    	public String getStringOID() {
    		String ret = "";
			for (int i = 0; i < lOID.length ; i++)
				ret += "." + lOID[i];
    		return ret;
    	}
    }
    */
    
    
    

    private class RequestContainer {
    	public long lOID[] = null;
    	public byte community[] = null;
    	public int pdutype = 0;
    	public static final int PDU_GET_NEXT = 2;
    	public static final int PDU_GET_THIS = 1;
    	public int requestID = 0;
    	public String OID = null;
    	
    	public boolean setPDU(byte id) {
    		switch(id) {
    		case (byte)0xA0:
    			pdutype = PDU_GET_THIS;
    		break;
    		
    		case (byte)0xA1:
    			pdutype = PDU_GET_NEXT;
    		break;
    		
    		default:
    			System.err.println("Unknown PDU: 0x" + Integer.toHexString((id + 256)%256));
    			return false;
    		}
    		return true;
    	}
    	
    	public String toString() {
    		return ("Community: " + new String(community) +
    				", PDU: " + pdutype + ", OID: " + OID);
    	}
    	
    	public boolean pduIsGet() {
    		return ((pdutype == RequestContainer.PDU_GET_THIS) || 
    				(pdutype == RequestContainer.PDU_GET_NEXT));
    	}
    }
}
