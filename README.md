# get-currency-rates

## Get currencies from specific HTML page and store it to PostgreSQL DB with time interval

This is simple console application made in learning purpose 

## What it actually do?

1) Opens HTML page https://www.dailyfx.com/forex-rates and read top left grid with currency cross rates 
2) Saves to PostgreSQL table public.exchange_rates (host, db, users, passwords and ports are customizeble )
3) By default app works 1 hour and request web site every 3 seconds

## How to build
I think it's possible to build without IDE but you should install Maven, Ant
Using IntelIiJ 
1) clone repository 
2) open project in IntelliJ
3) open customize project to java 1.8 (in project settings and settings of IDE)
4) if POM file is underlined with red line - upload Maven REPO from settigns after plugins should be OK
5) open terminal and type: mvn package - this will create in folder target all files
6) type ant deploy - this will create folder distr in root project folder with one JAR and folder parser

## Prerequsites 
 App use PhantomJS - special HTML parser which used to parse JavaScript updatable fileds on HTML. This emulate browser client side JS.
 Platform dependable!
 download from this [site] (http://phantomjs.org/download.html)
 

## How to use 
1) show help
'''
java -jar get-currency-web.jar -h
usage: This app parses HTML from URL: https://www.dailyfx.com/forex-rates
            and store cross currency rates into DB table
 -b,--binary-path <arg>   path to phantomjs binary
 -d,--db_name <arg>       postgresql DB name
 -H,--db_host <arg>       postgresql DB to store values
 -h,--help                show help
 -lc,--log-console        log to console
 -lf,--log-file           log to file
 -p,--db_port <arg>       postgresql port
 -t,--timeout <arg>       timeout to get data from site
 -U,--db_user <arg>       postgresql user
 -u,--uptime <arg>        period of time program will work
 -W,--db_password <arg>   postgresql password
 '''
 
run command like this (On Windows):

java -jar get-currency-web.jar -H 192.168.1.1 -p 5442 -U postgres -W postgres -d system_a -t 3 -u 1 -lf -b phantomjs.exe
this means

DB on             192.168.56.99 
PORT              5442
PASSWORD AND USER postgres
RETRY WEB REUQEST every 3 second
UPTIME            1 minute
USE ONLY FILE LOG 
PARSER            phantomjs.exe


