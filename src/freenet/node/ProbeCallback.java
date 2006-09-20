package freenet.node;

/** Callback for a locally initiated probe request */
public interface ProbeCallback {

	void onCompleted(String reason, double target, double best, double nearest, long id, short counter);

}
