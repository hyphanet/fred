package freenet.support;

import java.util.ArrayList;

public class RemoveRangeArrayList<T> extends ArrayList<T> {
	
	public RemoveRangeArrayList(int capacity) {
		super(capacity);
	}

	public void removeRange(int fromIndex, int toIndex) {
		super.removeRange(fromIndex, toIndex);
	}

}
