package freenet.client.async;

import java.lang.ref.SoftReference;
import java.util.ArrayList;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.node.PrioRunnable;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

// WARNING: THIS CLASS IS STORED IN DB4O -- THINK TWICE BEFORE ADD/REMOVE/RENAME FIELDS
class BackgroundBlockEncoderTag {
	final Encodeable inserter;
	final long nodeDBHandle;
	/** For implementing FIFO ordering */
	final long addedTime;
	/** For implementing priority ordering */
	final short priority;
	
	BackgroundBlockEncoderTag(Encodeable inserter, short prio, ClientContext context) {
		this.inserter = inserter;
		this.nodeDBHandle = context.nodeDBHandle;
		this.addedTime = System.currentTimeMillis();
		this.priority = prio;
	}
}
