package freenet.client.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;

/** A helper class for sending requests back to the server */
public class FreenetRequest {

	/**
	 * Sends a request to the given path with the given query parameters
	 * 
	 * @param path
	 *            - The path to send the request to
	 * @param parameters
	 *            - The parameters to send along with the request
	 * @return The sent request
	 */
	public static Request sendRequest(String path, QueryParameter[] parameters) {
		return sendRequest(path, parameters, null);
	}

	/**
	 * Sends a request to the given path with the given query parameter
	 * 
	 * @param path
	 *            - The path to send the request to
	 * @param parameter
	 *            - The parameter to send along with the request
	 * @return The sent request
	 */
	public static Request sendRequest(String path, QueryParameter parameter) {
		return sendRequest(path, new QueryParameter[] { parameter });
	}

	/**
	 * Sends a request to the given path with the given query parameter and a callback
	 * 
	 * @param path
	 *            - The path to send the request to
	 * @param parameter
	 *            - The parameter to send along with the request
	 * @param callback
	 *            - The callback that is registered to the request
	 * @return The sent request
	 */
	public static Request sendRequest(String path, QueryParameter parameter, RequestCallback callback) {
		return sendRequest(path, new QueryParameter[] { parameter }, callback);
	}

	/**
	 * Sends a request to the given path with the given query parameters and a callback
	 * 
	 * @param path
	 *            - The path to send the request to
	 * @param parameters
	 *            - The parameters to send along with the request
	 * @param callback
	 *            - The callback that is registered to the request
	 * @return The sent request
	 */
	public static Request sendRequest(String path, QueryParameter[] parameters, RequestCallback callback) {
		// FreenetJs.log("AJAX:path="+path+" queryParams:"+Arrays.asList(parameters));
		// A timestamp needs to be sent, because IE caches ajax
		if (parameters == null) {
			parameters = new QueryParameter[1];
		}
		List<QueryParameter> paramList = new ArrayList<QueryParameter>(Arrays.asList(parameters));
		paramList.add(new QueryParameter("timestamp", "" + System.currentTimeMillis()));
		// Assembles the query string
		String queryString = new String();
		queryString = queryString.concat("?");
		for (int i = 0; i < paramList.size(); i++) {
			queryString = queryString.concat(paramList.get(i).getName() + "=" + paramList.get(i).getValue());
			if (i != paramList.size() - 1) {
				queryString = queryString.concat("&");
			}
		}
		// If no callback was specified, then create an empty one
		if (callback == null) {
			callback = new RequestCallback() {

				@Override
				public void onResponseReceived(Request request, Response response) {
				}

				@Override
				public void onError(Request request, Throwable exception) {
				}
			};
		}
		try {
			// Create the request, send it, then return
			return new RequestBuilder(RequestBuilder.GET, path + queryString).sendRequest(null, callback);
		} catch (RequestException e) {
			// If error happened, then notify the callback and return null
			callback.onError(null, e);
			return null;
		}
	}
}
