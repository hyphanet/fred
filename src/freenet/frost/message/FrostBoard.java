package freenet.frost.message;

public final class FrostBoard {
	
	public static final int MAX_NAME_LENGTH = 64;
    
    private String boardName = null;

    private String publicKey = null;
    private String privateKey = null;
    
    /**
     * Constructs a new FrostBoardObject wich is a Board.
     */
    public FrostBoard(String name) {
        boardName = name;
    }

    /**
     * Constructs a new FrostBoardObject wich is a Board.
     * @param name
     * @param pubKey
     * @param privKey
     */
    public FrostBoard(String name, String pubKey, String privKey) {
        this(name);
        setPublicKey(pubKey);
        setPrivateKey(privKey);
    }

    public String getName() {
        return boardName;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public boolean isPublicBoard() {
        if (publicKey == null && privateKey == null)
            return true;
        return false;
    }

    public boolean isReadAccessBoard() {
        if (publicKey != null && privateKey == null) {
            return true;
        }
        return false;
    }

    public boolean isWriteAccessBoard() {
        if (publicKey != null && privateKey != null) {
            return true;
        }
        return false;
    }

    public void setPrivateKey(String val) {
        if (val.length()<5) val= null;
    	if (val != null) {
            val = val.trim();
        }
        privateKey = val;
    }

    public void setPublicKey(String val) {
    	if (val.length()<5) val= null;
        if (val != null) {
            val = val.trim();
        }
        publicKey = val;
    }
}
