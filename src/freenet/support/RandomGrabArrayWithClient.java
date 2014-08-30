package freenet.support;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequestSelector;
import freenet.client.async.WantsCooldownCallback;

public class RandomGrabArrayWithClient extends RandomGrabArray implements RemoveRandomWithObject {

	private Object client;
	
	public RandomGrabArrayWithClient(Object client, RemoveRandomParent parent, ClientRequestSelector root) {
		super(parent, root);
		this.client = client;
	}

	@Override
	public final Object getObject() {
	    synchronized(root) {
	        return client;
	    }
	}

	@Override
	public void setObject(Object client) {
	    synchronized(root) {
	        this.client = client;
	    }
	}
	
    @Override
    public void clearCooldownTime(ClientContext context) {
        super.clearCooldownTime(context);
        final Object c;
        synchronized(root) {
            c = client;
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
}
