public class grpInfo{
	public grpInfo(byte[] input){
        	grpInfo = new byte[input.length];
        	System.arraycopy(input,0,grpInfo,0,input.length);
	}
	private String encryptionAlgorithm;
        private String signatureAlgorithm;
        private String hashAlgorithm;
        private byte[] grpInfo;
	public void processData(){
        	if (grpInfo.length == 3)
		{
            		int enc = (new Byte(grpInfo[0])).intValue();
            		encryptionAlgorithm = getEncryptionAlgorithm(enc);
            		int sig = (new Byte(grpInfo[1])).intValue();
            		signatureAlgorithm  = getSignatureAlgorithm(sig);
            		int hash = (new Byte(grpInfo[2])).intValue();
            		hashAlgorithm = getHashAlgorithm(hash);
            		return;
            	}
            	else
			System.err.println("ERROR");
                  
        	

	/**
	 * Method getEncryptionAlgorithm returns the encryption algorithm name 
	 * extracted from the processData method.
	 * 
	 * @return String
	 */
	public String getEncryptionAlgorithm(int val){
        	return encryptionAlgorithm;
	}

	/**
	 * Method getSignatureAlgorithm returns the signature algorithm name 
	 * extracted from calling processData method.
	 * 
	 * @return String
	 */
	public String getSignatureAlgorithm(){
        	return signatureAlgorithm;
    	}

	/**
	 * Method getHashAlgorithm returns the hash algorithm name 
	 * extracted from calling processData method.
	 * 
	 * @return String
	 */
    	public String getHashAlgorithm(){
        	return hashAlgorithm;
    	}
}

