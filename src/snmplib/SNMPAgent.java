package snmplib;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
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
    	new Thread(_SNMPAgent).start();
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
    }
    
    public void addFetcher(DataFetcher df) {
    	DataHandler dh = new DataHandler(df);
    	alldata.put(dh.getStringOID(), dh);
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
        byte[] buf = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        while (socket.isBound()) {
            try {
                socket.receive(packet);

                RequestContainer rc = new RequestContainer();
                
                parseRequest(buf, rc);
                
                int replylength = 0;
                boolean keyfound = false;
                DataHandler dh = null;
                
                Iterator it = alldata.keySet().iterator();
                String key = "";
                if (rc.OID.length() == 0)
                	rc.OID = ".";
                
                while (it.hasNext() && !keyfound) {
                	key = (String)it.next();
                	if (key.startsWith(rc.OID))
                		keyfound = true;
                }

                // keyfound /\ (equal -> hasnext)
                if (keyfound && (!key.equals(rc.OID) || it.hasNext())) {
                	key = key.equals(rc.OID)?(String)it.next():key;
                	
                	dh = (DataHandler)alldata.get(key);
                	rc.lOID = (long[])dh.lOID.clone();

                	replylength = makeIntReply(buf, rc, dh.getData());
                } else {
                	rc.lOID[0] = 100;
                	replylength = makeIntReply(buf, rc, new Integer(1));
                }
                
                // send the response to the client at "address" and "port"
                InetAddress address = packet.getAddress();
                int port = packet.getPort();
                packet = new DatagramPacket(buf, replylength, address, port);
                socket.send(packet);
                
            } catch (IOException e) {
                e.printStackTrace();
                break;
            } catch (BadFormatException e) {
            	e.printStackTrace();
            	//System.err.println(e.toString());
            } catch (ArrayIndexOutOfBoundsException e) {
            	e.printStackTrace();
            	// not much to do.. ignore the request and it'll time out
            }
        }
        socket.close();
    }
    
    private int makeIntReply(byte buf[], RequestContainer rc, Object data) /* throws SnmpTooBigException */ {
    	int replyLength = 0;
    	BEREncoder be = new BEREncoder();
    	be.startSequence(); // whole pkg
    	be.putInteger(0); // version
    	be.putOctetString(rc.community); // community
    	be.startSequence((byte)0xa2); // Response
    	be.putInteger(rc.requestID); // RID
    	be.putInteger(0); // err
    	be.putInteger(0); // err
    	be.startSequence(); // value
    	be.startSequence(); // value
    	be.putOID(rc.lOID); // oid
    	
    	if (data instanceof Integer)
    		be.putInteger(((Integer)data).intValue());
    	else if (data instanceof Long)
    		be.putInteger(((Long)data).longValue());
    	else if (data instanceof String) {
    		char[] charr = ((String)data).toCharArray();
    		byte[] byarr = new byte[charr.length];
    		for (int i = 0 ; i < charr.length ; i++)
    			byarr[i] = (byte)charr[i];
    		be.putOctetString(byarr);
    	}
    	
    	replyLength = be.toBytes(buf);
    	
    	return replyLength;
    }
    
    // http://www.rane.com/note161.html
    private void parseRequest(byte buf[], RequestContainer rc) throws BadFormatException {
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
    	bd.startSequence();
    	rc.lOID = bd.fetchOID();
    	rc.OID = (rc.lOID.length == 0)?".":"";
    	for (int i = 0; i < rc.lOID.length ; i++)
    		rc.OID += "." + rc.lOID[i];
    	
    }

   
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
    			//System.err.println("Unknown PDU: 0x" + Integer.toHexString((id + 256)%256));
    			return false;
    		}
    		return true;
    	}
    	
    	public String toString() {
    		return ("Community: " + new String(community) +
    				", PDU: " + pdutype + ", OID: " + OID);
    	}
    }
}
