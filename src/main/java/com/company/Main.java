package com.company;

import com.google.common.base.Strings;
import com.sun.jna.platform.FileUtils;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.IOException;
import java.sql.*;

import org.apache.commons.cli.ParseException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;


import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;

import org.openqa.selenium.*;
import org.openqa.selenium.phantomjs.PhantomJSDriver;
import org.openqa.selenium.phantomjs.PhantomJSDriverService;
import org.openqa.selenium.remote.DesiredCapabilities;

public class Main {
    private static final java.util.logging.Logger MAIN_LOGGER = java.util.logging.Logger.getLogger( Main.class.getName() );

    static String CURR_URL = "https://www.dailyfx.com/forex-rates";
    static long DEFAULT_UPTIME_TIME_IN_MINUTES = 60;
    static Integer DEFAULT_REQUEST_INTERVAL_IN_SECONDS = 3;
    static long DEFAULT_BAD_RETRIES = 10000;
    static String INSERT_INTO_TABLE = "" +
            "insert into public.exchange_rates \n" +
            "(\n" +
            "\tdate,\n" +
            "\tnames,\n" +
            "\tval\n" +
            ")\n" +
            "select ?,?,?";

    static String CREATING_TABLE_QUERY = "" +
            "drop table if exists public.exchange_rates;\n" +
            "create table if not exists public.exchange_rates \n" +
            "(\n" +
            "\tid serial primary key,\n" +
            "\tdate timestamp default now(),\n" +
            "\tnames character varying(255),\n" +
            "\tval numeric(15,7)\n" +
            ");";

    static Options opts;
    static CommandLineParser cmd_parser ;
    static HelpFormatter hf ;
    static CommandLine cmd;

    static java.sql.Connection connection;
    static Integer DEBUG_TOTAL_INSERT_COUNT=0;
    public static void main(String[] args) {
        String args_str="";

        // init cmd options
        if ( parseCMD(args) == -1)
            return;

        if (cmd.hasOption("h"))
        {
            hf.printHelp("This app parses HTML from URL: " + CURR_URL+" and store cross currency rates into DB table",opts);
            return;
        }

        // init logger
        initLogger(args);

        MAIN_LOGGER.log(Level.CONFIG,"Program args: "+ (args_str.equals("")==true?"NO":args_str));

        // init connection
        if (checkAndCustomize() == -1)
        {
            MAIN_LOGGER.log(Level.SEVERE,"Error during checkAndCustomize. Exit");
            return;
        }

        ///PhantomJSDriverService driverService = PhantomJSDriverService.createDefaultService();

        WebDriver ghostDriver = new PhantomJSDriver();


        int retryAttempt =1;
        Integer BAD_REQUEST_COUNT=0;
        Integer interval = DEFAULT_REQUEST_INTERVAL_IN_SECONDS;


        try {
            if (cmd.hasOption("t"))
                interval = Integer.parseInt( cmd.getOptionValue("t"));
        }catch (Exception ex) {}

        MAIN_LOGGER.log(Level.CONFIG,"Request interval: " + interval.toString()+" sec");

        long upTime = DEFAULT_UPTIME_TIME_IN_MINUTES; //  ;

        try {
            if (cmd.hasOption("u"))
                upTime = Integer.parseInt( cmd.getOptionValue("u"));
        }catch (Exception ex) {}

        MAIN_LOGGER.log(Level.CONFIG,"Uptime : " + Long.toString(upTime)+" min");

        Map<String,Float> result_set = new HashMap<>();

        long startTime;
        long currTime;
        Integer requestCount= 0;

        startTime = System.currentTimeMillis();
        currTime = startTime;

        while ( currTime - startTime <= upTime * 60 * 1000 )
        {
            retryAttempt = 1;
            try {
                MAIN_LOGGER.log(Level.INFO,"Wait time interval: "+interval.toString() + " sec.");
                Thread.sleep(1000 * interval);
                MAIN_LOGGER.log(Level.INFO,"Elapsed time: " + String.valueOf( (currTime - startTime) / 1000  ).toString() + " " +
                        "sec. Remaining time: "+ String.valueOf( ( upTime * 60  - (currTime - startTime)/1000 ) ).toString()+" sec.");

            }catch (InterruptedException ex ){
                MAIN_LOGGER.log(Level.SEVERE,"Sleep failed! Interrupted exception:");
                MAIN_LOGGER.log(Level.SEVERE,"------------------------------------");
                MAIN_LOGGER.log(Level.SEVERE,ex.getMessage());
                ex.printStackTrace();
                MAIN_LOGGER.log(Level.SEVERE,"------------------------------------");
                Thread.currentThread().interrupt();
            }

            if (BAD_REQUEST_COUNT>DEFAULT_BAD_RETRIES) {
                MAIN_LOGGER.log(Level.SEVERE,"Exit because of to many errors! BAD_REQUEST_COUNT = " + BAD_REQUEST_COUNT.toString());
                break;
            }

            currTime = System.currentTimeMillis();

            while (retryAttempt <= 4) {
                try {
                    MAIN_LOGGER.log(Level.INFO,"Get data from web..START. Attempt "+String.valueOf(retryAttempt));
                    result_set.clear();
                    // clear cache
                    //ghostDriver.manage().timeouts().implicitlyWait(1000, TimeUnit.MILLISECONDS);
                    ghostDriver.manage().timeouts().setScriptTimeout(1500, TimeUnit.MILLISECONDS);
                    ghostDriver.manage().deleteAllCookies();
                    ghostDriver.get(CURR_URL);

                    requestCount++;
                    org.jsoup.nodes.Document doc = Jsoup.parse(ghostDriver.getPageSource());

                    Element body = doc.body();

                    Element curr_block = body.getElementsByClass("table dfx-calendar-table").first().
                            getElementsByTag("tbody").first();

                    Iterator<Element> i = curr_block.children().iterator();

                    while (i.hasNext()) {
                        Element tr_node = i.next();

                        if (tr_node.id() != null && tr_node.id() != "" && tr_node.id().contains("0"))
                            continue;

                        Elements td_nodes = tr_node.children();
                        String cross_curr_names = td_nodes.get(0).child(0).child(0).text();
                        Float cross_curr_rate = Float.parseFloat(td_nodes.get(2).child(0).text());
                        result_set.put(cross_curr_names, cross_curr_rate);

                        /// DEBUG
                        /// if (cross_curr_names.trim().equals("AUDNZD"))
                        ///   System.out.println(" "+cross_curr_rate.toString());
                    }

                    retryAttempt = 100000; // exit

                } catch (Exception ex) {
                    ex.printStackTrace();
                    retryAttempt++;
                    MAIN_LOGGER.log(Level.WARNING,"Error during parsing web document: \'" + CURR_URL + "\'. Retry: " + Integer.toString(retryAttempt));

                    if (retryAttempt ==5)
                        BAD_REQUEST_COUNT++;

                }
            }

            // add data to table
            if ( result_set.size() > 0 )
                try {
                    if ( connection.isClosed() )
                        MAIN_LOGGER.log(Level.SEVERE,"Connection closed! Error! Check code");

                    java.util.Date utilDate = new java.util.Date();
                    java.sql.Timestamp sqlTimestamp = new java.sql.Timestamp(utilDate.getTime());
                    MAIN_LOGGER.log(Level.INFO,"Putting web data to table...START");
                    for (Map.Entry<String, Float> j : result_set.entrySet()) {
                        PreparedStatement insert_data = connection.prepareStatement(INSERT_INTO_TABLE);
                        insert_data.setTimestamp(1, sqlTimestamp );
                        insert_data.setString(2, j.getKey() );
                        insert_data.setFloat(3, j.getValue() );
                        insert_data.execute();
                    }
                    DEBUG_TOTAL_INSERT_COUNT++;
                    MAIN_LOGGER.log(Level.INFO,"DEBUG_TOTAL_INSERT_COUNT = "+DEBUG_TOTAL_INSERT_COUNT.toString());
                    MAIN_LOGGER.log(Level.INFO,"Putting web data to table...END");
                } catch (SQLException ex) {
                    ex.printStackTrace();
                    printSQLException(ex);
                }
        }

        try {
            connection.close();
        } catch (SQLException ex) {
            ex.printStackTrace();
            printSQLException(ex);
        }


        MAIN_LOGGER.log(Level.CONFIG,"App finished. Requests count: " + requestCount.toString());
    }


    private static int checkAndCustomize() {

        // set default path for phantomjs binary we need to parse html with java script updatable content
        String phantomjs_path="C:\\app\\PhantomJS\\phantomjs.exe";



        if (cmd.hasOption("b"))
            phantomjs_path = cmd.getOptionValue("b");

        try {
            File file = new File(phantomjs_path);
            if (!file.exists()) {
                MAIN_LOGGER.log(Level.SEVERE, "phantomjs_path:'" + phantomjs_path.toString() + "' does not exists!Exit");
                return -1;
            }
        }catch (Exception ex){
            MAIN_LOGGER.log(Level.SEVERE, "phantomjs_path:'" + phantomjs_path.toString() + "' does not exists!Exit");
            ex.printStackTrace();
            return -1;
        }

        // setting variable phantomjs.binary.path to path to phantomjs binnary (for windows phantomjs.exe)
        System.setProperty("phantomjs.binary.path", phantomjs_path); // path to bin file. NOTE: platform dependent

        //setting connection to PostgreSQL server

        try
        {
            // if arguments were not set - exit
            String connectionUrl = "";
            if (!cmd.hasOption("H")||!cmd.hasOption("d")||!cmd.hasOption("U")||!cmd.hasOption("W"))
            {
                MAIN_LOGGER.log(Level.SEVERE,"Arguments were not set correctly!Exit");
                hf.printHelp("get_data_from_web",opts);
                return -1;
            }

            String port =  (Strings.isNullOrEmpty(cmd.getOptionValue("p"))==true)? "5432":cmd.getOptionValue("p");
            String host =  cmd.getOptionValue("H");
            String db =  cmd.getOptionValue("d");
            String user =  cmd.getOptionValue("U");
            String pass =  cmd.getOptionValue("W");

            connectionUrl =
                    "jdbc:postgresql://"+host+":"+ port  +"/"+db+"?user="+user +"&password="+pass;

            Class.forName("org.postgresql.Driver");
            connection = DriverManager.getConnection(connectionUrl);
            connection.setAutoCommit(true);

        }
        catch (SQLException ex)
        {
            MAIN_LOGGER.log(Level.SEVERE,"Error while connection to DB via URL ");
            printSQLException(ex);
            ex.printStackTrace();
            return -1;
        }
        catch (ClassNotFoundException ex)
        {
            ex.printStackTrace();
            MAIN_LOGGER.log(Level.SEVERE,"Cant initialize postgresql driver");
            return -1;
        }
        catch (Exception ex)
        {
            ex.printStackTrace();
            MAIN_LOGGER.log(Level.SEVERE,"Troubles! Exit! See log above");
            return -1;
        }

        // check table exist
        try {
            Statement sql = connection.createStatement();
            sql.execute("select count (*) from pg_tables where schemaname||'.'||tablename = 'public.exchange_rates';");
            ResultSet rs = sql.getResultSet();
            rs.next();
            Integer table_count = rs.getInt(1);

            if (table_count>0)
                MAIN_LOGGER.log(Level.CONFIG,"Table with name ''public.exchange_rates'' exists");
            else
            {
                MAIN_LOGGER.log(Level.CONFIG,"Table with name ''public.exchange_rates'' NOT exists.");
                Statement create_sql = connection.createStatement();
                create_sql.execute(CREATING_TABLE_QUERY);
            }

        }
        catch (SQLException ex)
        {
            MAIN_LOGGER.log(Level.SEVERE,"Error while creating table ''public.exchange_rates''!");
            ex.printStackTrace();
            printSQLException(ex);
            return -1;
        }

        return 0;
    }

    private static int parseCMD(String[] args){
        opts = new Options();

        opts.addOption( new Option("b","binary-path", true,"path to phantomjs binary"));
        opts.addOption( new Option("H","db_host", true,"postgresql DB to store values"));
        opts.addOption( new Option("h","help", false,"show help"));
        opts.addOption( new Option("d","db_name", true,"postgresql DB name"));
        opts.addOption( new Option("U","db_user", true,"postgresql user"));
        opts.addOption( new Option("W","db_password", true,"postgresql password"));
        opts.addOption( new Option("p","db_port", true,"postgresql port"));
        opts.addOption( new Option("t","timeout", true,"timeout to get data from site"));
        opts.addOption( new Option("u","uptime", true,"period of time program will work"));
        opts.addOption( new Option("lf","log-file", false,"log to file"));
        opts.addOption( new Option("lc","log-console", false,"log to console"));

        // Init parser of command line
        cmd_parser = new DefaultParser();
        hf = new HelpFormatter();

        try {
            cmd = cmd_parser.parse(opts, args);

        }catch (ParseException pe) {
            System.out.println("Can't parse arguments!! Exit");
            pe.printStackTrace();
            return -1;
        }

        return 0;
    }

    public static void printSQLException(SQLException ex) {
    // from JAVA tutorial
        for (Throwable e : ex) {
            if (e instanceof SQLException) {
                if (ignoreSQLException(
                        ((SQLException)e).
                                getSQLState()) == false) {

                    e.printStackTrace(System.err);
                    MAIN_LOGGER.log(Level.SEVERE,"SQLState: " +
                            ((SQLException)e).getSQLState());

                    MAIN_LOGGER.log(Level.SEVERE,"Error Code: " +
                            ((SQLException)e).getErrorCode());

                    MAIN_LOGGER.log(Level.SEVERE,"Message: " + e.getMessage());

                    Throwable t = ex.getCause();
                    while(t != null) {
                        MAIN_LOGGER.log(Level.SEVERE,"Cause: " + t);
                        t = t.getCause();
                    }
                }
            }
        }
    }

    public static boolean ignoreSQLException(String sqlState) {
        // from JAVA tutorial
        if (sqlState == null) {
            MAIN_LOGGER.log(Level.SEVERE,"The SQL state is not defined!");
            return false;
        }

        // X0Y32: Jar file already exists in schema
        if (sqlState.equalsIgnoreCase("X0Y32"))
            return true;

        // 42Y55: Table already exists in schema
        if (sqlState.equalsIgnoreCase("42Y55"))
            return true;

        return false;
    }
    static void initLogger(String[] args)
    {

        LoggerFormatter kettleLogFormatter = new  LoggerFormatter();
        FileHandler fileHandler = null;

        // ConsoleHandler
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(kettleLogFormatter);
        consoleHandler.setLevel( Level.FINE );

        //FileHandler
        try {
            fileHandler = new FileHandler("web_parse.log");
            fileHandler.setFormatter(kettleLogFormatter);
            fileHandler.setLevel( Level.FINE );
        } catch (SecurityException e1) {
            System.out.println("Error in initializing FileHandler for Logger");
            e1.printStackTrace();
        } catch (IOException e1) {
            System.out.println("Error in initializing FileHandler for Logger");
            e1.printStackTrace();

        }

        // set logger level
        MAIN_LOGGER.setLevel(Level.FINE);


        if ( cmd.hasOption("lf") )
           MAIN_LOGGER.addHandler(fileHandler);

        if ( cmd.hasOption("lc") )
           MAIN_LOGGER.addHandler(consoleHandler);

        // set handlers
        if (!cmd.hasOption("lf")&&!cmd.hasOption("lc"));
            MAIN_LOGGER.addHandler(fileHandler);

    }
}
