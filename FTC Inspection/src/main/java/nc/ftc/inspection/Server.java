package nc.ftc.inspection;

import static spark.Spark.*;
import static spark.debug.DebugScreen.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;

import nc.ftc.inspection.dao.UsersDAO;
import nc.ftc.inspection.spark.util.Filters;
import nc.ftc.inspection.spark.util.Path;
import nc.ftc.inspection.spark.util.ViewUtil;
import spark.template.velocity.*;

import spark.Route;

public class Server {
	public static final String GLOBAL_DB = "jdbc:sqlite:src/main/resources/db/global.db"; 
	public static void main(String[] args) {
		try {//idk, somethings up with gradle but this makes it work.
			Class.forName("org.sqlite.JDBC");
		} catch (ClassNotFoundException e1) {
			e1.printStackTrace();
		}
		
		port(80);
		staticFiles.location("/public");
		enableDebugScreen();
		
		before("*", Filters.addTrailingSlashes);
		
		get(Path.Web.INDEX, (req, res) -> {
			HashMap<String, Object> map = new HashMap<String, Object>();
			map.put("currTime", System.currentTimeMillis());
			return ViewUtil.render(req, map , Path.Template.INDEX)	;
		});
		get("*", ViewUtil.notFound);
		
		after("*", Filters.addGzipHeader);
	}
}

