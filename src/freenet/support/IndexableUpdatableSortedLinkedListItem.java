package freenet.support;

public interface IndexableUpdatableSortedLinkedListItem<T extends IndexableUpdatableSortedLinkedListItem<T>> extends
		UpdatableSortedLinkedListItem<T> {

	public Object indexValue();

}
