package freenet.keys;

import freenet.crypt.CryptFormatException;

public class PubkeyVerifyException extends KeyVerifyException {

	public PubkeyVerifyException(CryptFormatException e) {
		super(e);
	}

}
