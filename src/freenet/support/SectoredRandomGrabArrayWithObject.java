package freenet.support;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestSelector;
import freenet.client.async.WantsCooldownCallback;

public class SectoredRandomGrabArrayWithObject extends SectoredRandomGrabArray implements RemoveRandomWithObject {

	private Object object;
	
	public SectoredRandomGrabArrayWithObject(Object object, RemoveRandomParent parent, ClientRequestSelector root) {
		super(parent, root);
		this.object = object;
	}

	@Override
	public Object getObject() {
	    synchronized(root) {
	        return object;
	    }
	}
	
	@Override
	public String toString() {
		return super.toString()+":"+object;
	}

	@Override
	public void setObject(Object client) {
	    synchronized(root) {
	        object = client;
	    }
	}
	
	   
    @Override
    public void clearCooldownTime(ClientContext context) {
        super.clearCooldownTime(context);
        final Object c;
        synchronized(root) {
            c = object;
        }
        if(c instanceof WantsCooldownCallback) {
            context.mainExecutor.execute(new Runnable() {

                @Override
                public void run() {
                    ((WantsCooldownCallback)c).clearCooldown();
                }
                
            });
        }
    }
    
    @Override
    public void reduceCooldownTime(final long wakeupTime, final ClientContext context) {
        super.reduceCooldownTime(wakeupTime, context);
        final Object c;
        synchronized(root) {
            c = object;
        }
        if(c instanceof WantsCooldownCallback) {
            context.mainExecutor.execute(new Runnable() {
                
                @Override
                public void run() {
                    ((WantsCooldownCallback)c).enterCooldown(wakeupTime, context);
                }
                
            });
        }
    }
    
}
