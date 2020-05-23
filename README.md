# Scale POC for Spring batch

ETL application is
 - using Spring Batch to run jobs
 - configured for N jobs
 - deployed in K8 using `deployment` i.e. stateless
    - VM Arguments and resource allocations cannot be set per replica.

To scale this ETL application, i.e. set `replica` to more than 1, this application needs
- to possess X tokens
    - Since it is stateless deployment, all replicas will have token initialized to X
- Assumption : X >= N
- During start-up, each instance uses dynamo-db-lock-client to acquire X locks for jobs and initializes fixedDelay scheduler these jobs
- For this to work, `rollingUpdate` deployment should be used with `maxSurge` set to 0. i.e. only when you bring down the existing instance, new instance can acquire some locks

Refer to the documentation [here](http://wiki.ia55.net/display/TECHDOCS/Distributed+ETL+-+V1)  

## Installing Postgres

<details>
<summary>Postgres Linux Installation</summary>

#### Set your local postgres working directory - do this in every new terminal window / shell
`export TEST_PG_ROOT=/codemill/$USER/postgres`
#### Create postgres working directory if it doesn't exist - this only needs to be done once
`mkdir -p $TEST_PG_ROOT`
#### Download Postgres .tar.gz - this only needs to be done once
* `wget https://sbp.enterprisedb.com/getfile.jsp?fileid=12354 -O $TEST_PG_ROOT/pgsql.tar.gz`
* `tar xzf $TEST_PG_ROOT/pgsql.tar.gz`
#### Initialize the database - this only needs to be done once
* `rm -rf $TEST_PG_ROOT/db`
* `mkdir -p $TEST_PG_ROOT/db`
* `$TEST_PG_ROOT/pgsql/bin/initdb $TEST_PG_ROOT/db`
#### Start a local postgres server - this runs postgres in the foreground, can be shut down with ctrl-c and restarted as many times as you'd like
`$TEST_PG_ROOT/pgsql/bin/postgres -D $TEST_PG_ROOT/db --unix_socket_directories=$TEST_PG_ROOT --wal_level=minimal --archive_mode=off --max_wal_senders=0 --checkpoint_timeout=30 --archive_command=/bin/true --max_wal_size=256MB`
#### Connect via psql - optional to test that your server works. You can also use dbeaver
`$TEST_PG_ROOT/pgsql/bin/psql --host=$TEST_PG_ROOT -d postgres`
#### Create user and assign password and provide owner permissions to run DDLs via flyway - username & password should match the properties mentioned in application.properties
```
 CREATE USER postgres SUPERUSER;
 CREATE DATABASE scale_poc WITH OWNER postgres;
 ALTER USER postgres WITH PASSWORD 'admin';
```
#### Create schema datasync in scale_poc DB
```
$TEST_PG_ROOT/pgsql/bin/psql -U postgres
\l <to list database>
\c <db_name> 
CREATE SCHEMA IF NOT EXISTS datasync; 
```

</details>

-----------------
<details>
<summary>Postgres windows installation</summary>

* initdb
```
C:\Users\vino\Downloads\pgsql\bin>initdb -D "C:\Users\vino\Downloads\pgsql\datadir"
The files belonging to this database system will be owned by user "vino".
This user must also own the server process.

The database cluster will be initialized with locale "English_United States.1252".
The default database encoding has accordingly been set to "WIN1252".
The default text search configuration will be set to "english".

Data page checksums are disabled.

creating directory C:/Users/vino/Downloads/pgsql/datadir ... ok
creating subdirectories ... ok
selecting dynamic shared memory implementation ... windows
selecting default max_connections ... 100
selecting default shared_buffers ... 128MB
selecting default time zone ... Asia/Calcutta
creating configuration files ... ok
running bootstrap script ... ok
performing post-bootstrap initialization ... ok
syncing data to disk ... ok

initdb: warning: enabling "trust" authentication for local connections
You can change this by editing pg_hba.conf or using the option -A, or
--auth-local and --auth-host, the next time you run initdb.

Success. You can now start the database server using:

    pg_ctl -D ^"C^:^\Users^\vino^\Downloads^\pgsql^\datadir^" -l logfile start
```
* Start postgres server	
```
C:\Users\vino\Downloads\pgsql\bin>pg_ctl -D "C:\Users\vino\Downloads\pgsql\datadir" start
```

* Use client (or use dbeaver) and give permissions to user "postgres" and assign password too.
```
C:\Users\vino\Downloads\pgsql\bin>psql -d postgres
psql (12.2)
WARNING: Console code page (437) differs from Windows code page (1252)
         8-bit characters might not work correctly. See psql reference
         page "Notes for Windows users" for details.
Type "help" for help.

postgres=# CREATE USER postgres SUPERUSER;
CREATE ROLE
postgres=# ALTER USER postgres WITH PASSWORD 'admin';
ALTER ROLE
postgres=# CREATE DATABASE scale_poc WITH OWNER postgres;
```

* Create schema in scale_poc DB
```
C:\Users\vino\Downloads\pgsql\bin>psql -U postgres
postgres=# \c scale_poc
You are now connected to database "scale_poc" as user "postgres".
scale_poc=# create schema if not exists datasync;
```

</details>

------------------

<details>
<summary>Connecting to postgres from Dbeaver</summary>

* New -> Dbeaver -> Database Connection -> PostgreSQL
* Host : localhost 
* Port : 5432
* Database : scale_poc
* User : postgres
* password : admin (select save password locally)
</details>

---------

## Note

* If you already have postgres installed, revisit the above steps to create database (scale_poc), schema (datasync), user (postgres) and password (admin) which aligns with [application.properties](/src/main/resources/application.properties)

    ```
    spring.datasource.url=jdbc:postgresql://localhost:5432/scale_poc?currentSchema=datasync
    spring.datasource.hikari.username=postgres
    spring.datasource.hikari.password=admin
    ```

* You would need table created in Dynamo DB. Override the dynamodb properties at [application.properties](/src/main/resources/application.properties) with your own account details
    ```
    dynamodb.accesskey=
    dynamodb.secretkey=
    dynamodb.region=
    dynamodb.tablename=
    dynamodb.partitionKey=
    ``` 
* To run the app, Just Run the main method at [ScalePocApplication](src/main/java/com/foo/ScalePocApplication.java)
    - token is set to 2 at `application.properties`
    - Total jobs present = 4
    - So you can run this app twice or more to observe the behavior
    - Change the token to 1 or 3 to test for other cases too.

## Monitoring story - Pending

Where can things go wrong?
* Every job configured should have locks possessed by some instance.

* Note that once the lock is acquired, the heart beat is sent via a background thread. So possessing lock != active jobs. What else should we ensure to say our jobs are healthy?
   * the scheduler thread running per job should be active AND executing jobs IF the jobs are not paused.

Approach :

* One is self monitoring. i.e. every instance checks that there are job executions in the batch metadata for the jobs it acquired locks for.
    * The "how" part is pretty simple. You know the status - paused/unpaused. If the job is not paused, check the schedule and compare with the last job execution for that job
    
* Another is global monitoring - i.e. there is one exclusive job run by one or more instances (which can in turn be configured as a job with a lock assigned - eg: monitoring-1 and monitoring-2 each running at different intervals)
    * This checks that locks for **all** datasets are possessed by some instance.
    * How? simply by trying to acquiring lock for all jobs one by one. If this monitoring job can acquire lock, it means that it is idle.
    * To do this, we need to ensure that the lock client used by this check is different - i.e. lease duration should be lower and no automatic heart beats. 