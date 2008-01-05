package freenet.keys;

import freenet.crypt.CryptFormatException;

public class PubkeyVerifyException extends KeyVerifyException {

	public PubkeyVerifyException(CryptFormatException e) {
		super(e);
	}

	public PubkeyVerifyException(String msg) {
		super(msg);
	}

}
