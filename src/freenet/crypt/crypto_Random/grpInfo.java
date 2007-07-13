public class grpInfo{
	public grpInfo(byte[] input){
        	grpInfo = new byte[input.length];
        	System.arraycopy(input,0,grpInfo,0,input.length);
	}
	private String encryptionAlgorithm;
        private String signatureAlgorithm;
        private String hashAlgorithm;
        private byte[] grpInfo=new byte[3];
	public void processData(){
        	if (grpInfo.length == 3)
		{
            		int enc = grpInfo[0] & 0xFF;
            		encryptionAlgorithm = getEncryptionAlgorithm(enc);
            		int sig = grpInfo[1] & 0xFF;
            		signatureAlgorithm  = getSignatureAlgorithm(sig);
            		int hash = grpInfo[2] & 0xFF;
            		hashAlgorithm = getHashAlgorithm(hash);
            		return;
            	}
            	else
			System.err.println("ERROR");
        }          
        	

	/**
	 * Method getEncryptionAlgorithm returns the encryption algorithm name 
	 * extracted from the processData method.
	 * 
	 * @return String
	 */
	public String getEncryptionAlgorithm(int encVal){
        	return algorithm.getAlgo(encVal);
	}

	/**
	 * Method getSignatureAlgorithm returns the signature algorithm name 
	 * extracted from calling processData method.
	 * 
	 * @return String
	 */
	public String getSignatureAlgorithm(int sigVal){
        	return algorithm.getAlgo(sigVal);
    	}

	/**
	 * Method getHashAlgorithm returns the hash algorithm name 
	 * extracted from calling processData method.
	 * 
	 * @return String
	 */
    	public String getHashAlgorithm(int hashVal){
        	return algorithm.getAlgo(hashVal);
    	}
}

