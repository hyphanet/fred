package freenet.crypt;

public class SHA1Factory implements DigestFactory {

    public Digest getInstance() {
	return SHA1.getInstance();
    }
    
}
