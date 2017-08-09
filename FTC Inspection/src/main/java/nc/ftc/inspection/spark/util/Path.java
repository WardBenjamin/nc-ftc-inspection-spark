package nc.ftc.inspection.spark.util;


public class Path {

    // The @Getter methods are needed in order to access
    // the variables from Velocity Templates
    public static class Web {
        public static final String INDEX = "/index/";
        public static final String LOGIN = "/login/";
        public static final String CHANGE_PW = "/changepw/";
        public static final String LOGOUT = "/logout/";
        public static final String ERROR_403 = "/error403/";
        public static final String IP_PAGE = "/ip/";
        public static final String CREATE_ACCOUNT_SIMPLE = "/createaccount/";
        public static final String CREATE_ACCOUNT = CREATE_ACCOUNT_SIMPLE + ":id/";
		public static final String ALL = "*";
        
        public String getIndex() {
        	return INDEX;
        }
        public String getConsole() {
        	return "";
        }
        public String getInspection() {
        	return "";
        }
        public String getLogout() {
        	return LOGOUT;
        }
        public String getLogin() {
        	return LOGIN;
        }
    }

    public static class Template {
        public final static String INDEX = "/velocity/index/index.vm";
        public final static String LOGIN = "/velocity/index/login.vm";
        public final static String BOOKS_ALL = "/velocity/book/all.vm";
        public static final String BOOKS_ONE = "/velocity/book/one.vm";
        public static final String NOT_FOUND = "/velocity/notFound.vm";
        public static final String IP_PAGE = "/velocity/index/ip.vm";
        public static final String ERROR_403 = "/velocity/403.vm";
        public static final String CREATE_ACCOUNT = "/velocity/users/createAccount.vm";
    }

}