package freenet.node.stats;

/**
 * This class represents one instance of data store.
 * Instance is described by two properties: key type and store type.
 * <p/>
 * User: nikotyan
 * Date: Apr 16, 2010
 */
public class DataStoreInstanceType {
	public final DataStoreType store;
	public final DataStoreKeyType key;

	public DataStoreInstanceType(DataStoreKeyType key, DataStoreType store) {
		this.store = store;
		this.key = key;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		DataStoreInstanceType that = (DataStoreInstanceType) o;

		if (key != that.key) return false;
		if (store != that.store) return false;

		return true;
	}

	@Override
	public int hashCode() {
		int result = store.hashCode();
		result = 31 * result + key.hashCode();
		return result;
	}

	@Override
	public String toString() {
		return "DataStoreInstanceType{" +
				"store=" + store +
				", key=" + key +
				'}';
	}
}
