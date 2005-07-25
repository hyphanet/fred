package freenet.node;

/**
 * @author root
 *
 * TODO To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
public class StaticSwapRequestInterval implements SwapRequestInterval {

    double fixedInterval;
    
    public StaticSwapRequestInterval(double d) {
        fixedInterval = d;
    }
    
    public double getValue() {
        return fixedInterval;
    }
}
