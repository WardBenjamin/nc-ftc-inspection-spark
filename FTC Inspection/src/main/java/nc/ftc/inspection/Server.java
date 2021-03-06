/*
 * Copyright (c) 2016-2018 Thomas Barnette and Trey Woodlief
 * All Rights Reserved.
 */

package nc.ftc.inspection;

import static spark.Spark.*;
import static spark.debug.DebugScreen.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.BindException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nc.ftc.inspection.dao.ConfigDAO;
import nc.ftc.inspection.dao.EventDAO;
import nc.ftc.inspection.dao.GlobalDAO;
import nc.ftc.inspection.dao.UsersDAO;
import nc.ftc.inspection.event.Event;
import nc.ftc.inspection.event.StatsCalculator;
import nc.ftc.inspection.event.StatsCalculator.StatsCalculatorJob;
import nc.ftc.inspection.model.Match;
import nc.ftc.inspection.model.User;
import nc.ftc.inspection.spark.pages.DefaultPages;
import nc.ftc.inspection.spark.pages.EventPages;
import nc.ftc.inspection.spark.pages.GlobalPages;
import nc.ftc.inspection.spark.pages.LoginPage;
import nc.ftc.inspection.spark.pages.ServerPages;
import nc.ftc.inspection.spark.util.Filters;
import nc.ftc.inspection.spark.util.Path;

/**
 * The Server class contains the main method that is run on startup. It is responsible for 
 * configuring and launching the Spark webserver. This includes mapping each URL to the appropriate
 * REST endpoint handler, applying the user filters to each endpoint, and taking the startup parameters
 * and configuring the system appropriately.
 *
 */
public class Server {
	/**The directory containing all the database files*/
	public static final String DB_PATH;// = "src/main/resources/db/";
	/**The target directory for any archive files generated by the system*/
	public static final String ARCHIVE_PATH;
	/**The database file containing all data not specific to any one event*/
	public static final String GLOBAL_DB;// = "jdbc:sqlite:"+DB_PATH+"global.db"; 
	/**The database file containing all data related to inter-server communication. This database shoudl never be shared between instances.*/
	public static final String CONFIG_DB;
	/**The default port the web server runs on**/
	private static final Integer FTCLIVE_DEFAULT_PORT = 80;
	/**The firectory of all the resources files: audio, images, etc.*/
	public static File publicDir;

	/**Maps event code to Event object for in-RAM cache of data. Currently only for live-scoring, not inspection.
	*/
	public static Map<String, Event> activeEvents = new HashMap<>();
	/**If the user does not specify an event, the server will assume that this is the event they mean
		For the local server, this should be the event it is at, and for the remote server, there should be no default
	*/
	public static String defaultEventCode = "test11";
	

	public static Logger log;
	public static boolean redirected = false;
	public static void redirectError() {
		if(redirected)return;
		//If not in eclipse, use the -Ddb parameter to set the DB. If in eclipse, use default.
		String loc = System.getProperty("location");
		if(loc != null && loc.equals("external")){
			File logDir = new File("logs");
			if(!logDir.exists() || !logDir.isDirectory()) {
				logDir.mkdirs();
			}
			File logFile = new File("logs/"+System.currentTimeMillis()+"_err.log");
			try {
				logFile.createNewFile();
			} catch (IOException e1) {
				System.err.println("Error creating log file!");
				e1.printStackTrace();
			}
			try {
				System.out.println("Redirecting error stream to log file "+logFile.getName());
				PrintStream pw = new PrintStream(logFile);
				System.setErr(pw);
			} catch (FileNotFoundException e) {
				System.err.println("Error redirecting System.err!");
				e.printStackTrace();
			}
		}
		log = LoggerFactory.getLogger(Server.class);
		redirected = true;
	}
	/**
	 * Static initialization of path constants from the -D JVM parameters.
	 */
	static{ 
		
		if(!redirected) {
			redirectError();
		}	
		
		String db = System.getProperty("db");
		DB_PATH = db == null ? "src/main/resources/db/" : db;
		String archive = DB_PATH;
		archive = archive.substring(0, archive.length() - 2);
		int ind = archive.lastIndexOf('/');
		if(ind >= 0)archive = archive.substring(0, ind);
		ARCHIVE_PATH = archive + "/archive/";
		log.debug("DB Path set to: {}", DB_PATH);
		log.debug("Archive Path set to: {}", ARCHIVE_PATH);
		GLOBAL_DB = "jdbc:sqlite:"+DB_PATH+"global.db"; 
		CONFIG_DB = "jdbc:sqlite:"+DB_PATH+"config.db";
	}

	protected static CommandLine parseOptions(String[] args) {

		Options options = new Options();
		Option port = new Option("p", "port", true, "port to run server on");
		port.setRequired(false);
		port.setType(Integer.class);
		options.addOption(port);

		CommandLineParser parser = new DefaultParser();
		HelpFormatter helpFormatter = new HelpFormatter();
		CommandLine cmd;

		try {
			cmd = parser.parse(options, args);
			return cmd;
		} catch (ParseException e) {
			System.out.println(e.getMessage());
			helpFormatter.printHelp("launch.bat", options);
			System.exit(1);
		}
		return null;
	}

	/**
	 * Run on startup. No parameters are passed to main this way. All parameters are passed via -D 
	 * JVM parameters.
	 * @param args Runtime arguments - none handled.
	 */
	public static void main(String[] args) {

		Integer port = FTCLIVE_DEFAULT_PORT;

		try {//idk, somethings up with gradle but this makes it work.
			Class.forName("org.sqlite.JDBC");
		} catch (ClassNotFoundException e1) {
			e1.printStackTrace();
		}

		CommandLine cmd = parseOptions(args);
		if (cmd.hasOption('p')) {
			try {
				port = Integer.parseInt(cmd.getOptionValue("port", Integer.toString(FTCLIVE_DEFAULT_PORT)));
			} catch (NumberFormatException e) {
				System.out.println("Port argument must be an integer, using 80 as default");
			}
		}
		System.out.println("Starting server on port " + port);

		ConfigDAO.runStartupCheck();
		RemoteUpdater.getInstance();//force classloader
		Runtime.getRuntime().addShutdownHook(RemoteUpdater.getHook());
		EventDAO.loadActiveEvents();
		for(Entry<String, Event> e : activeEvents.entrySet()) {
			try {
				e.getValue().calculateRankings();
				
			}catch(Exception e1) {
				log.error("Cant calculate rankings for {}", e.getKey());
			}
			try {
				StatsCalculator.enqueue(new StatsCalculatorJob(e.getValue(), StatsCalculatorJob.ELIMS));
			}catch(Exception e2) {
				log.error("Error calculating elims stats for {}", e.getKey());
			}
		}
	//	threadPool(100, 30, 0);
		initExceptionHandler((e) -> {
			if(e instanceof BindException) {
				System.out.println("Bind Error! Port 80 already in use!");
				System.out.println("Please check that another instance of the live scoring system or other web server is not running.");
				System.out.println("Use the Resource Monitor to check for processes listening on TCP 80 and kill them.");
			} else {
				System.out.println("Error occured while launching server!");
				System.out.println(e.getClass().getName());
				System.out.println(e.getMessage());
			}
			System.out.println("Please terminate (CTRL+C in this window) and resolve the issue, then restart the server.");
		}
		);
		port(port);
		//Use the -Dlocation argument to determine if inside eclipse or running from a built .jar.
		String loc = System.getProperty("location");
		String publicLoc;
		if(loc != null && loc.equals("external")){
			publicLoc = "src/main/resources/public";
			staticFiles.externalLocation(publicLoc);
			log.info("External Static Files");		
			
		} else {
			publicLoc = "public";
			staticFiles.location(publicLoc);
			log.info("Internal Static Files");
			enableDebugScreen();
		}
		publicDir = new File("src/main/resources/public");
		
		before("*", Filters.addTrailingSlashesAndLowercase);
		before("*", Filters.createSession);		
		
		get(Path.Web.DEFAULT, DefaultPages.forwardTo(Path.Web.INDEX));
		get(Path.Web.INDEX, DefaultPages.indexPage);
		get(Path.Web.LOGIN, LoginPage.serveLoginPage);
		post(Path.Web.LOGIN, LoginPage.handleLoginPost);
		post(Path.Web.LOGOUT, LoginPage.handleLogoutPost);
		get(Path.Web.IP_PAGE, DefaultPages.ipPage);
		get(Path.Web.FEEDBACK, GlobalPages.serveFeedbackForm);
		post(Path.Web.FEEDBACK, GlobalPages.handleFeedback);
		get(Path.Web.SCHEDULE, EventPages.serveSchedulePage);
		get(Path.Web.ERROR_403, DefaultPages.error403);
		get(Path.Web.EVENT_STATUS_PAGE, EventPages.serveStatusPage);
		get(Path.Web.PIT_DISPLAY, EventPages.servePitPage);
		get(Path.Web.QUEUE_DISPLAY, EventPages.serveQueuePage);
		get(Path.Web.RANKINGS, EventPages.handleGetRankings);
		get(Path.Web.MATCH_RESULTS, EventPages.serveResultsPage);
		get(Path.Web.RESULTS_NAME, EventPages.serveResultsNamePage);
		get(Path.Web.TEAM_MATCH_INFO, EventPages.serveTeamResultsPage);
		get(Path.Web.MATCH_RESULTS_SIMPLE, EventPages.serveResultsSimplePage);
		get(Path.Web.MATCH_RESULTS_DETAILS, EventPages.serveResultsDetailPage);
		get(Path.Web.EVENT_HOME, EventPages.serveEventHomePage);
		get(Path.Web.EVENT_SIMPLE, DefaultPages.forwardTo("./home/"));
		//I am unsure about the ones below here
		get(Path.Web.GET_RANDOM, EventPages.handleGetRandom);
		get(Path.Web.WAIT_FOR_REFS, EventPages.handleWaitForRefs);
		get(Path.Web.WAIT_FOR_START, EventPages.handleWaitForStart);
		get(Path.Web.WAIT_FOR_MATCH_END, EventPages.handleWaitForEnd);
		get(Path.Web.WAIT_FOR_COMMIT, EventPages.handleWaitForCommit);
		get(Path.Web.GET_FULL_SCORESHEET, EventPages.handleGetFullScoresheet);
		get(Path.Web.GET_FULL_SCORESHEET_PLAIN, EventPages.handleGetFullScoresheet);
		get(Path.Web.GET_ALLIANCE_BREAKDOWN, EventPages.handleGetAllianceBreakdown);

		//THESE ARE GENERAL USERS BUT NO ONE SHOULD EVER SEE THEM DIRECTLY BC THEY ARE REST
		get(Path.Web.MASTER_TEAM_LIST, GlobalPages.handleTeamListGet);
		get(Path.Web.EVENT_STATUS, EventPages.handleGetStatusGet);
		//I am unsure about the ones below here
		get(Path.Web.SCORE, EventPages.handleGetScore);
		get(Path.Web.SCORE_BREAKDOWN, EventPages.handleGetScoreBreakdown);
		get(Path.Web.SCHEDULE_STATUS, EventPages.handleGetScheduleStatus);
		get(Path.Web.GET_MATCH, EventPages.handleGetCurrentMatch);
		get(Path.Web.BOTH_SCORE, EventPages.handleGetFullScore);
		get(Path.Web.GET_MATCH_FULL, EventPages.handleGetFullResult);
		get(Path.Web.GET_MATCH_INFO, EventPages.handleGetMatchInfo);
		
		//User Pages - Must be logged in
		before(Path.Web.CHANGE_PW, Filters.getAuthenticationFilter());
		get(Path.Web.CHANGE_PW, LoginPage.servePasswordChangePage);
		post(Path.Web.CHANGE_PW, LoginPage.handlePasswordChangePost);
		
		before(Path.Web.USER_PAGE, Filters.getAuthenticationFilter());
		get(Path.Web.USER_PAGE, LoginPage.serveUserPage );
		
		
		//Inspection Pages - Must be inspector
		before(Path.Web.INSPECT, Filters.getAuthenticationFilter(User.INSPECTOR));
		get(Path.Web.INSPECT, EventPages.serveInspectionPage);
		
		before(Path.Web.INSPECT_HOME, Filters.getAuthenticationFilter(User.INSPECTOR));
		get(Path.Web.INSPECT_HOME, EventPages.serveInspectionHome);
		
		before(Path.Web.INSPECT_ITEM, Filters.getAuthenticationFilter(User.INSPECTOR));
		post(Path.Web.INSPECT_ITEM, EventPages.handleInspectionItemPost);
		
		//Team Inspection Pages
		//before(Path.Web.INSPECT_TEAM_HOME, Filters.getAuthenticationFilter(User.TEAM));
		get(Path.Web.INSPECT_TEAM_HOME, EventPages.serveTeamInspectionHome);
		
		//before(Path.Web.INSPECT_TEAM_FORM, Filters.getAuthenticationFilter(User.TEAM));
		get(Path.Web.INSPECT_TEAM_FORM, EventPages.serveInspectionPageReadOnly);
		get(Path.Web.INSPECT_TEAM_FORM_PLAIN, EventPages.serveInspectionPageReadOnly);
		
		//TODO we need to talk about the permissions here
		get(Path.Web.TEAM_INFO, EventPages.serveTeamInfo);
		//LRI Pages
		before(Path.Web.EDIT_FORM, Filters.getAuthenticationFilter(User.LI));
		get(Path.Web.EDIT_FORM, EventPages.serveFormEditPage);
		
		before(Path.Web.INSPECT_OVERRIDE, Filters.getAuthenticationFilter(User.LI));
		get(Path.Web.INSPECT_OVERRIDE, EventPages.serveInspectionOverride);
		
		
		//TODO encrypt passwords on POST
		
		//Head Ref pages
		before(Path.Web.HEAD_REF, Filters.getAuthenticationFilter(User.HEAD_REF));
		get(Path.Web.HEAD_REF, EventPages.serveHeadRef);
		
		//Ref Pages
		before(Path.Web.REF, Filters.getAuthenticationFilter(User.REF));
		get(Path.Web.REF, EventPages.serveRef);
		before(Path.Web.REF_IDLE, Filters.getAuthenticationFilter(User.REF));
		get(Path.Web.REF_IDLE, EventPages.serveIdleRef);
		

		//ADMIN Pages
		before(Path.Web.EDIT_PERMISSIONS, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.EDIT_PERMISSIONS, LoginPage.serveEditPermissionsPage);
		post(Path.Web.EDIT_PERMISSIONS, LoginPage.handleEditPermissionsPost);
		
		before(Path.Web.DELETE_USERS, Filters.getAuthenticationFilter(User.ADMIN));
		post(Path.Web.DELETE_USERS, LoginPage.handleDeleteUsers);
		
		
		before(Path.Web.MATCH_CONTROL, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.MATCH_CONTROL, EventPages.serveMatchControlPage);
		
		before(Path.Web.AUDIENCE_DISPLAY, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.AUDIENCE_DISPLAY, EventPages.serveAudienceDisplay);
		
		before(Path.Web.FIELD_DISPLAY, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.FIELD_DISPLAY, EventPages.serveFieldDisplay);
		
		before(Path.Web.OVERLAY, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.OVERLAY, EventPages.serveOverlay);
		
		before(Path.Web.MATCH_PREVIEW, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.MATCH_PREVIEW, EventPages.handleWaitForPreview);
		post(Path.Web.MATCH_PREVIEW, EventPages.handleShowPreview);
		
		before(Path.Web.GET_POST_RESULTS_INFO, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.GET_POST_RESULTS_INFO, EventPages.handlePostResultData);
		
		before(Path.Web.SHOW_RESULTS, Filters.getAuthenticationFilter(User.ADMIN));
		post(Path.Web.SHOW_RESULTS, EventPages.handleShowResults);
		
		before(Path.Web.SHOW_RESULTS_OLD, Filters.getAuthenticationFilter(User.ADMIN));
		post(Path.Web.SHOW_RESULTS_OLD, EventPages.handleShowOldResults);
		
		before(Path.Web.GET_TIMER_COMMANDS, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.GET_TIMER_COMMANDS, EventPages.handleGetTimerCommands);
		
		before(Path.Web.GET_DISPLAY_COMMANDS, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.GET_DISPLAY_COMMANDS, EventPages.handleGetDisplayCommands);
		
		before(Path.Web.EDIT_MATCH_SCORE, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.EDIT_MATCH_SCORE, EventPages.handleGetEditScorePage);
		post(Path.Web.EDIT_MATCH_SCORE, EventPages.handleCommitEditedScore);
		put(Path.Web.EDIT_MATCH_SCORE, EventPages.handleGetEditedScore);
		
		before(Path.Web.MANAGE_EVENT, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.MANAGE_EVENT, EventPages.serveManagePage);
		before(Path.Web.EVENT_SETTINGS, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.EVENT_SETTINGS, EventPages.serveEventSettings);
		before(Path.Web.SET_OVERLAY_DEFAULT, Filters.getAuthenticationFilter(User.ADMIN));
		put(Path.Web.SET_OVERLAY_DEFAULT, EventPages.handleOverlayColorSet);
		before(Path.Web.DELETE_DATA, Filters.getAuthenticationFilter(User.ADMIN));
		delete(Path.Web.DELETE_DATA, EventPages.handleDeleteData);
		before(Path.Web.DELETE_MATCHES, Filters.getAuthenticationFilter(User.ADMIN));
		delete(Path.Web.DELETE_MATCHES, EventPages.handleDeleteMatches);
		
		before(Path.Web.ZIP_EVENT, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.ZIP_EVENT, EventPages.serveZipFile);
		
		before(Path.Web.MANAGE_EVENT_TEAMS, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.MANAGE_EVENT_TEAMS, EventPages.serveAddTeam);
		post(Path.Web.MANAGE_EVENT_TEAMS, EventPages.handleAddTeam);
		delete(Path.Web.MANAGE_EVENT_TEAMS, EventPages.handleRemoveTeam);
		put(Path.Web.MANAGE_EVENT_TEAMS, EventPages.handleEditTeam);
		
		before(Path.Web.UPLOAD_SCHEDULE, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.UPLOAD_SCHEDULE, EventPages.serveUploadSchedulePage);
		
		before(Path.Web.UPLOAD_DIVISION_WINNERS, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.UPLOAD_DIVISION_WINNERS, EventPages.serveDivisonUploadPage);
		post(Path.Web.UPLOAD_DIVISION_WINNERS, EventPages.handleDivisionUpload);
		
		before(Path.Web.EDIT_SCORE_HOME, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.EDIT_SCORE_HOME, EventPages.serveEditScoreHome);
		
		before(Path.Web.CREATE_EVENT, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.CREATE_EVENT, EventPages.serveEventCreationPage);
		post(Path.Web.CREATE_EVENT, EventPages.handleEventCreationPost);
		
		before(Path.Web.CREATE_ACCOUNT_SIMPLE, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.CREATE_ACCOUNT_SIMPLE, LoginPage.serveCreateAccountPage);
		post(Path.Web.CREATE_ACCOUNT_SIMPLE, LoginPage.handleCreateAccountPost);
		
		before(Path.Web.CREATE_ACCOUNT, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.CREATE_ACCOUNT, LoginPage.serveCreateAccountPage);
		post(Path.Web.CREATE_ACCOUNT, LoginPage.handleCreateAccountPost);
		
		before(Path.Web.NEW_TEAM, Filters.getAuthenticationFilter(User.ADMIN));
		post(Path.Web.NEW_TEAM, GlobalPages.handleNewTeamPost);
		put(Path.Web.EDIT_TEAM, GlobalPages.handleNewTeamPost);
		
		
		//headref | admin
		post(Path.Web.RANDOMIZE, EventPages.handleRandomizePost);
		post(Path.Web.RERANDOMIZE, EventPages.handleReRandomizePost);
		
		//admin? - yes, except this would block the get to rankings
		get(Path.Web.GET_TIME, EventPages.getMatchTime);
		post(Path.Web.UPLOAD_SCHEDULE, "multipart/form-data", EventPages.handleScheduleUpload);
		post(Path.Web.START_MATCH, EventPages.handleStartMatch);
		post(Path.Web.PAUSE_MATCH, EventPages.handlePauseMatch);
		post(Path.Web.RESUME_MATCH, EventPages.handleResumeMatch);
		post(Path.Web.RESET_MATCH, EventPages.handleResetMatch);
		post(Path.Web.SUBMIT_SCORE, EventPages.handleScoreSubmit);
		post(Path.Web.COMMIT_SCORES, EventPages.handleScoreCommit);
		post(Path.Web.SCORE, EventPages.handleTeleopSubmit);
		post(Path.Web.SCORE_AUTO, EventPages.handleAutoSubmit);
		post(Path.Web.LOAD_MATCH, EventPages.handleLoadMatch);
		post(Path.Web.SHOW_PREVIEW, EventPages.handleShowPreviewCommand);
		post(Path.Web.SHOW_MATCH, EventPages.handleShowMatch);
		post(Path.Web.LOCKOUT_REFS, EventPages.handleLockoutRefs);
		put(Path.Web.SCORE, EventPages.handleScoreUpdate);
		put(Path.Web.EDIT_SCORE, EventPages.handleControlScoreEdit);
		post(Path.Web.SET_STATUS, EventPages.handleSetStatus);
		post(Path.Web.TIMEOUT_COMMAND,  EventPages.handleTimeoutCommand);
		
		post(Path.Web.RANKINGS, EventPages.handleRecalcRankings);
		
		//ref?
		put(Path.Web.INSPECT_NOTE, EventPages.handleNote);
		put(Path.Web.INSPECT_SIG, EventPages.handleSig);
		put(Path.Web.INSPECT_STATUS, EventPages.handleFormStatus);
		
		
		//Leave open. authentication handled every request by access keys.
		post(Path.Web.REMOTE_POST, ServerPages.handleRemoteUpdatePost);
		post(Path.Web.VERIFY, ServerPages.handleVerify);
		//leave open... cuz ping.
		get(Path.Web.PING, ServerPages.handlePing);
		
		
		before(Path.Web.SERVER_CONFIG, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.SERVER_CONFIG, ServerPages.serveConfigPage);		

		before(Path.Web.REMOTE_KEYS, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.REMOTE_KEYS, ServerPages.serveRemoteKeyPage);
		post(Path.Web.REMOTE_KEYS, ServerPages.handleSaveRemoteKey);
		put(Path.Web.REMOTE_KEYS, ServerPages.handlTestRemote);
		delete(Path.Web.REMOTE_KEYS, ServerPages.handleDeleteRemoteKey);
		
		before(Path.Web.CLIENT_KEYS, Filters.getAuthenticationFilter(User.SYSADMIN));
		get(Path.Web.CLIENT_KEYS, ServerPages.serveClientKeyPage);
		post(Path.Web.CLIENT_KEYS, ServerPages.handleGenerateKey);
		delete(Path.Web.CLIENT_KEYS, ServerPages.handleDeleteClientKey);
		
		
		before(Path.Web.DATA_DOWNLOAD, Filters.getAuthenticationFilter(User.ADMIN));
		post(Path.Web.DATA_DOWNLOAD, ServerPages.handleDataDownloadPost);
		
		//leave open, authentication done via access key.
		post(Path.Web.DATA_DOWNLOAD_GLOBAL, ServerPages.handleDataDownloadGlobal);
		post(Path.Web.DATA_DOWNLOAD_EVENT, ServerPages.handleDataDownloadEvent);
		
		before(Path.Web.UPLOAD_ALLIANCES, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.UPLOAD_ALLIANCES, EventPages.serveAllianceUploadPage);
		post(Path.Web.UPLOAD_ALLIANCES, EventPages.handleAllianceUpload);
		
		before("/event/:event/selection/*", Filters.getAuthenticationFilter(User.ADMIN));
		post(Path.Web.SELECTION, EventPages.handleSelection);
		post(Path.Web.START_SELECTION, EventPages.handleStartSelection);
		post(Path.Web.CLEAR_SELECTION, EventPages.handleClearSelection);
		post(Path.Web.UNDO_SELECTION, EventPages.handleUndoSelection);
		post(Path.Web.SAVE_SELECTION, EventPages.handleSaveSelection);
		get(Path.Web.GET_SELECTION_INFO, EventPages.handleGetSelectionData);
		
		get(Path.Web.STATS, EventPages.serveStats);
		
		
		before(Path.Web.SERVER_DATA_MANAGEMENT, Filters.getAuthenticationFilter(User.ADMIN));
		before(Path.Web.DELETE_EVENT, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.SERVER_DATA_MANAGEMENT, ServerPages.serveServerDataPage);
		post(Path.Web.DELETE_EVENT, ServerPages.handleDeleteEventPost);
		before(Path.Web.EXPORT_USERS, Filters.getAuthenticationFilter(User.ADMIN));
		get(Path.Web.EXPORT_USERS, ServerPages.handleExportUsers);
		post(Path.Web.EXPORT_USERS, ServerPages.handleImportUsers);
		before(Path.Web.IMPORT_TEAMS_TO_MASTER, Filters.getAuthenticationFilter(User.ADMIN));
		post(Path.Web.IMPORT_TEAMS_TO_MASTER, ServerPages.handleImportTeams);
		before(Path.Web.IMPORT_TEAM_LIST, Filters.getAuthenticationFilter(User.ADMIN));
		post(Path.Web.IMPORT_TEAM_LIST, EventPages.handleImportTeamList);
		
		get(Path.Web.ALL, DefaultPages.notFound);
		
		after("*", Filters.addGzipHeader);
		
	
		
		/* TODO might want to record a snapshot of thread and ram count every 30 se or so
		 * and keep like 20 minutes of data
		 * have a page that shows the graph, the names of all the current threads & maybe their state?
		 * need to do some timing on responses - those random ~8s times on updateScore is concerning.
		
		Thread t = new Thread("Resource Monitor") {
			public void run() {
				while(true) {
					try {
						Thread.sleep(5000);
					}catch(Exception e) {
						
					}
					int threads = Thread.activeCount();
					Runtime r = Runtime.getRuntime();
					long free = r.freeMemory();
					long total = r.totalMemory();
					long ram = total - free;
					double perc = 100.0 * ((double)ram) / ((double)total);
					System.out.println(threads +" threads");
					
					System.out.println(Spark.activeThreadCount() + " spark threads");
					System.out.println("RAM: "+(ram / 1024 / 1024) + "/" + (total/1024/1024)+ " MB ("+perc+"%)");
					System.out.println("****************");
				}
			}
		};
		t.start();
		*/
	}
}

