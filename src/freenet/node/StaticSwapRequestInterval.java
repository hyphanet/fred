package freenet.node;

public class StaticSwapRequestInterval implements SwapRequestInterval {

    int fixedInterval;
    
    public StaticSwapRequestInterval(int d) {
        fixedInterval = d;
    }
    
    public int getValue() {
        return fixedInterval;
    }

	public void set(int val) {
		this.fixedInterval = val;
	}
}
