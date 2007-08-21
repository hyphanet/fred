package freenet.crypt;
import java.io.*;
import java.util.*;
import java.lang.Integer;
import freenet.crypt.HMAC;
public class session_Key
{
	
	/**
          *Session key.
          *The key is generated from Hash of Message:(Ni, Nr, 0) using the DF exponentials
          *@param Ni: nonce from the initiator
          *@param Nr: nonce from the responder
          */
        public static byte[] getSessionKey(byte[] DFExp,byte[] Ni, byte[] Nr)
	{
        	try
		{
            		byte[] byteArray=new byte[Ni.length + Nr.length + 1];
            		System.arraycopy(Ni,0,byteArray,0,Ni.length);
            		System.arraycopy(Nr,0,byteArray,Ni.length,Nr.length);
            		byteArray[Ni.length + Nr.length]=(new Integer(0)).byteValue();
			HMAC s = new HMAC(SHA1.getInstance());
			return s.mac(DFExp,byteArray,DFExp.length);
        	}catch(Exception e){
            		System.err.println("Exception:" + e);
            		System.exit(1);
            		return null;
        	}
    	}
}
