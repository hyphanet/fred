package freenet.support;

import java.util.ArrayList;

public class RemoveRangeArrayList<T> extends ArrayList<T> {
	
	private static final long serialVersionUID = -1L;

	public RemoveRangeArrayList(int capacity) {
		super(capacity);
	}

	@Override
	public void removeRange(int fromIndex, int toIndex) {
		super.removeRange(fromIndex, toIndex);
	}

}
