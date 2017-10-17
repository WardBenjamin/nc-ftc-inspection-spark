package nc.ftc.inspection.model;

public class User {
	
	public static int SYSADMIN = 1<<31;
	public static int ADMIN = 1<<30;
	public static int KEY_VOLUNTEER = 1<<29;
	public static int VOLUNTEER = 1<<2;
	public static int TEAM = 1<<1;
	public static int GENERAL = 1<<0;
	
	public static int NONE = 0; //this is for if you are not logged in
	
	
	private String username;
	private String realName;
	private String hashedPw;
	private String salt;
	private int type;
	private boolean changedPw;
	
	public boolean hasChangedPw() {
		return changedPw;
	}

	public void setChangedPw(boolean changedPw) {
		this.changedPw = changedPw;
	}

	public User(String username, String hashedPw, String salt, int type, String rn, boolean changed){
		this.username = username;
		this.hashedPw = hashedPw;
		this.salt = salt;
		this.type = type;
		this.realName = rn;
		this.changedPw = changed;
	}
	
	public String getUsername() {
		return username;
	}
	public void setUsername(String username) {
		this.username = username;
	}
	public String getHashedPw() {
		return hashedPw;
	}
	public void setHashedPw(String hashedPw) {
		this.hashedPw = hashedPw;
	}
	public String getSalt() {
		return salt;
	}
	public void setSalt(String salt) {
		this.salt = salt;
	}
	public int getType() {
		return type;
	}
	public void setType(int type) {
		this.type = type;
	}
	public String getRealName() {
		return realName;
	}
	public void setRealName(String realName) {
		this.realName = realName;
	}

	/**
	 * This checks to see if this users type is at least the type given.
	 * @param type The type to check against
	 * @return if this user is at least the type given
	 */
	public boolean is(int type) {
		//the check for this.type == type is to allow for 0 == 0 for general
		return (this.type & type) != 0 || this.type == type;
	}
	

}
