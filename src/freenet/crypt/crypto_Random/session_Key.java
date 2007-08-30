package freenet.crypt.crypto_Random;
import freenet.crypt.HMAC;
import freenet.crypt.SHA1;
public class session_Key
{
	
        public session_Key(){
            
        }
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
            		byte[] byteArray = new byte[Ni.length + Nr.length + 1];
            		System.arraycopy(Ni,0,byteArray,0,Ni.length);
            		System.arraycopy(Nr,0,byteArray,Ni.length,Nr.length);
            		byteArray[Ni.length + Nr.length] = (byte)0;
			HMAC s = new HMAC(SHA1.getInstance());
			return s.mac(DFExp,byteArray,DFExp.length);
        	}catch(Exception e){
            		System.err.println("Exception:" + e);
            		System.exit(1);
            		return null;
        	}
    	}
}
