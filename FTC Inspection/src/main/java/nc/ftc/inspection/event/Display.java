package nc.ftc.inspection.event;

import nc.ftc.inspection.model.MatchResult;

public class Display {
	public Object displayCommandLock = new Object();
	DisplayCommand lastCommand = DisplayCommand.SHOW_MATCH;
	public MatchResult lastResult;
	public int red1Dif;
	public int red2Dif;
	public int blue1Dif;
	public int blue2Dif;
	
	public DisplayCommand getLastCommand() {
		return lastCommand;
	}
	
	public void issueCommand(DisplayCommand cmd) {
		this.lastCommand = cmd;
		synchronized(displayCommandLock) {
			displayCommandLock.notifyAll();
		}
	}
	
	public DisplayCommand blockForNextCommand() throws InterruptedException {
		synchronized(displayCommandLock) {
			displayCommandLock.wait();
		}
		return lastCommand;
	}
}