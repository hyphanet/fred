package freenet.client.messages;

public class Message {

	private String msg;
	
	private Priority priority;
	
	private String anchor;

	public Message(String msg,Priority priority,String anchor) {
		super();
		this.msg = msg;
		this.priority=priority;
		this.anchor=anchor;
	}

	public String getMsg() {
		return msg;
	}

	public Priority getPriority() {
		return priority;
	}

	public String getAnchor() {
		return anchor;
	}
	
	@Override
	public boolean equals(Object obj) {
		if(obj instanceof Message){
			Message message=(Message)obj;
			return msg.compareTo(message.msg)==0 && priority==message.priority && ((anchor==null && message.anchor==null) || anchor!=null && message.anchor!=null && anchor.compareTo(message.anchor)==0);
		}else{
			return false;
		}
	}
}
