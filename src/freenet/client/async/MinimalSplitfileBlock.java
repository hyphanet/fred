package freenet.client.async;

import freenet.client.SplitfileBlock;
import freenet.support.io.Bucket;

public class MinimalSplitfileBlock implements SplitfileBlock {

	public final int number;
	Bucket data;
	
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

}
