/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class ConfigCallback<T> {

	/**
	 * Create a config callback from lambdas. Needs to be specialized
	 * in Subtypes of ConfigCallback to match the nodeConfig.register API.
	 *
	 * @param set accepts the new value and return an Exception or null (if no exception)
	 */
	public static <T> ConfigCallback<T> from(Supplier<T> get, Function<T, Exception> set) {
		return new ConfigCallback<T>() {

			@Override
			public T get() {
			  return get.get();
			}

			@Override
			public void set(T value) throws InvalidConfigValueException, NodeNeedRestartException {
			  Exception e = set.apply(value);
			  if (e instanceof InvalidConfigValueException) {
				throw (InvalidConfigValueException) e;
			  }
			  if (e instanceof NodeNeedRestartException) {
				throw (NodeNeedRestartException) e;
			  }
			}
		};
	}


	/**
	 * Create a config callback that cannot throw exceptions from
	 * lambdas. Needs to be specialized in Subtypes of ConfigCallback
	 * to match the nodeConfig.register API.
	 *
	 * @param set accepts the new value.
	 */
	public static <T> ConfigCallback<T> from(Supplier<T> get, Consumer<T> set) {
		return from(get, (value) -> {
		  set.accept(value);
		  return null;
		});
	}

	/**
	 * Get the current, used value of the config variable.
	 */
	public abstract T get();

	/**
	 * Set the config variable to a new value.
	 * 
	 * @param val
	 *            The new value.
	 * @throws InvalidConfigOptionException
	 *             If the new value is invalid for this particular option.
	 */
	public abstract void set(T val) throws InvalidConfigValueException, NodeNeedRestartException;
	
	public boolean isReadOnly() {
		return false;
	} 
}
