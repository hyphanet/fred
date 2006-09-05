package freenet.crypt;

public abstract class KeyAgreementSchemeContext {
    protected long lastUsedTime;
    protected boolean logMINOR;
    
    /**
     * @return The time at which this object was last used.
     */
    public synchronized long lastUsedTime() {
        return lastUsedTime;
    }
}
