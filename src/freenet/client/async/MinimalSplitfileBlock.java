package freenet.client.async;

import com.db4o.ObjectContainer;

import freenet.client.SplitfileBlock;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

public class MinimalSplitfileBlock implements SplitfileBlock {

	public final int number;
	Bucket data;
	boolean flag;
	
	public MinimalSplitfileBlock(int n) {
		this.number = n;
	}

	public int getNumber() {
		return number;
	}

	public boolean hasData() {
		return data != null;
	}

	public Bucket getData() {
		return data;
	}

	public void setData(Bucket data) {
		this.data = data;
	}

	// Useful for debugging duplicate object bugs. But use the new logging infrastructure if you reinstate it, please.
//	public void objectOnDeactivate(ObjectContainer container) {
//		if(Logger.shouldLog(LogLevel.MINOR, this))
//			Logger.minor(this, "Deactivating "+this, new Exception("debug"));
//	}
//
	public void storeTo(ObjectContainer container) {
		if(Logger.shouldLog(LogLevel.MINOR, this))
			Logger.minor(this, "Storing "+this+" with data: "+data);
		if(data != null)
			data.storeTo(container);
		container.store(this);
	}

	public void removeFrom(ObjectContainer container) {
		if(data != null) {
			container.activate(data, 1);
			data.removeFrom(container);
		}
		container.delete(this);
	}

}
