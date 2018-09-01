package freenet.support;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestSelector;

/** Second level tree node. At the base we have RGA's. Then we have these, SRGAs containing RGAs.
 * Then we have SRGAs containing SRGAs.
 * @author toad
 */
public class SectoredRandomGrabArraySimple<MyType,ChildType> extends SectoredRandomGrabArrayWithObject<MyType, ChildType, RandomGrabArrayWithObject<ChildType>> {

    private static volatile boolean logMINOR;
    
    static {
        Logger.registerClass(SectoredRandomGrabArraySimple.class);
    }
    
    public SectoredRandomGrabArraySimple(MyType object, RemoveRandomParent parent,
            ClientRequestSelector root) {
        super(object, parent, root);
    }

    /** Add directly to a RandomGrabArrayWithObject under us. */
    public void add(ChildType client, RandomGrabArrayItem item, ClientContext context) {
        synchronized(root) {
        RandomGrabArrayWithObject<ChildType> rga = getGrabber(client);
        if(rga == null) {
            if(logMINOR)
                Logger.minor(this, "Adding new RGAWithClient for "+client+" on "+this+" for "+item);
            rga = new RandomGrabArrayWithObject<ChildType>(client, this, root);
            addElement(client, rga);
        }
        if(logMINOR)
            Logger.minor(this, "Adding "+item+" to RGA "+rga+" for "+client);
        rga.add(item, context);
        if(context != null) {
            clearWakeupTime(context);
        }
        if(logMINOR)
            Logger.minor(this, "Size now " + size() + " on " + this);
        }
    }

}
