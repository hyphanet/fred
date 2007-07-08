import freenet.crypt;
import java.util.*;
public class nonceGen{
	private byte nonce[];
	public nonceGen(int size){
		nonce=new byte[size];
	}
	public byte[] getNonce() throws Exception{
		/*
		 * We get this from node.random rather than instantiating Yarrow
		 */
		node.random.nextBytes(nonce);
		return nonce;
	}
}
		
