package freenet.support;

public interface RemoveRandomWithObject<T> extends RemoveRandom {

	public T getObject();

	public boolean isEmpty();

	public void setObject(T client);

}
