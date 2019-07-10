/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.util.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class provides convenience methods to execute SQL statements. They can be either executed in isolation
 * or within the context of a JDBC transaction; the so-called <i>batch mode</i> (use the {@link #startBatch()}
 * and {@link #endBatch(boolean)} methods for this).
 * 
 * <p/>
 * 
 * This class contains logic to retry execution of SQL statements. If this helper is <i>not</i> in batch mode
 * and if a statement fails due to an {@code SQLException}, then it is retried. If the {@code block} argument
 * of the constructor call was {@code false} then it is retried only once. Otherwise the statement is retried
 * until either it succeeds or the thread is interrupted. This clearly assumes that the only cause of {@code
 * SQLExceptions} is faulty {@code Connections} which are restored eventually. <br/> <strong>Note</strong>:
 * This retry logic only applies to the following methods:
 * <ul>
 * <li>{@link #exec(String, Object...)}</li>
 * <li>{@link #update(String, Object[])}</li>
 * <li>{@link #exec(String, Object[], boolean, int)}</li>
 * </ul>
 * 
 * <p/>
 * 
 * This class is not thread-safe and if it is to be used by multiple threads then the clients must make sure
 * that access to this class is properly synchronized.
 * 
 * <p/>
 * 
 * <strong>Implementation note</strong>: The {@code Connection} that is retrieved from the {@code DataSource}
 * in {@link #getConnection()} may be broken. This is so because if an internal {@code DataSource} is used,
 * then this is a commons-dbcp {@code DataSource} with a <code>testWhileIdle</code> validation strategy (see
 * the {@code ConnectionFactory} class). Furthermore, if it is a {@code DataSource} obtained through JNDI then we
 * can make no assumptions about the validation strategy. This means that our retry logic must either assume that
 * the SQL it tries to execute can do so without errors (i.e., the statement is valid), or it must implement its
 * own validation strategy to apply. Currently, the former is in place.
 */
public class ConnectionHelper {

    private static Logger log = LoggerFactory.getLogger(ConnectionHelper.class);

    private static final int RETRIES = 1;

    private static final int SLEEP_BETWEEN_RETRIES_MS = 100;

    private final boolean blockOnConnectionLoss;

    private final boolean checkTablesWithUserName;

    protected final DataSource dataSource;

    private ThreadLocal<Connection> batchConnectionTl = new ThreadLocal<Connection>();

    /**
     * @param dataSrc the {@link DataSource} on which this instance acts
     * @param block whether the helper should transparently block on DB connection loss (otherwise it retries
     *            once and if that fails throws exception)
     */
    public ConnectionHelper(DataSource dataSrc, boolean block) {
        dataSource = dataSrc;
        checkTablesWithUserName = false;
        blockOnConnectionLoss = block;
    }

    /**
     * @param dataSrc the {@link DataSource} on which this instance acts
     * @param checkWithUserName whether the username is to be used for the {@link #tableExists(String)} method
     * @param block whether the helper should transparently block on DB connection loss (otherwise it throws exceptions)
     */
    protected ConnectionHelper(DataSource dataSrc, boolean checkWithUserName, boolean block) {
        dataSource = dataSrc;
        checkTablesWithUserName = checkWithUserName;
        blockOnConnectionLoss = block;
    }
    
    /**
     * A utility method that makes sure that <code>identifier</code> does only consist of characters that are
     * allowed in names on the target database. Illegal characters will be escaped as necessary.
     * 
     * This method is not affected by the
     * 
     * @param identifier the identifier to convert to a db specific identifier
     * @return the db-normalized form of the given identifier
     * @throws SQLException if an error occurs
     */
    public final String prepareDbIdentifier(String identifier) throws SQLException {
        if (identifier == null) {
            return null;
        }
        String legalChars = "ABCDEFGHIJKLMNOPQRSTUVWXZY0123456789_";
        legalChars += getExtraNameCharacters();
        String id = identifier.toUpperCase();
        StringBuffer escaped = new StringBuffer();
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (legalChars.indexOf(c) == -1) {
                replaceCharacter(escaped, c);
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * Called from {@link #prepareDbIdentifier(String)}. Default implementation replaces the illegal
     * characters with their hexadecimal encoding.
     * 
     * @param escaped the escaped db identifier
     * @param c the character to replace
     */
    protected void replaceCharacter(StringBuffer escaped, char c) {
        escaped.append("_x");
        String hex = Integer.toHexString(c);
        escaped.append("0000".toCharArray(), 0, 4 - hex.length());
        escaped.append(hex);
        escaped.append("_");
    }

    /**
     * Returns true if we are currently in a batch mode, false otherwise.
     * @return true if the current thread is running in batch mode, false otherwise.
     */
    protected boolean inBatchMode()
    {
      return batchConnectionTl.get() != null;
    }

    /**
     * The default implementation returns the {@code extraNameCharacters} provided by the databases metadata.
     * 
     * @return the additional characters for identifiers supported by the db
     * @throws SQLException on error
     */
    private String getExtraNameCharacters() throws SQLException {
        Connection con = dataSource.getConnection();
        try {
            DatabaseMetaData metaData = con.getMetaData();
            return metaData.getExtraNameCharacters();
        } finally {
            DbUtility.close(con, null, null);
        }
    }

    /**
     * Checks whether the given table exists in the database.
     * 
     * @param tableName the name of the table
     * @return whether the given table exists
     * @throws SQLException on error
     */
    public final boolean tableExists(String tableName) throws SQLException {
        Connection con = dataSource.getConnection();
        ResultSet rs = null;
        boolean schemaExists = false;
        String name = tableName;
        try {
            DatabaseMetaData metaData = con.getMetaData();
            if (metaData.storesLowerCaseIdentifiers()) {
                name = tableName.toLowerCase();
            } else if (metaData.storesUpperCaseIdentifiers()) {
                name = tableName.toUpperCase();
            }
            String userName = null;
            if (checkTablesWithUserName) {
                userName = metaData.getUserName();
            }
            rs = metaData.getTables(null, userName, name, null);
            schemaExists = rs.next();
        } finally {
            DbUtility.close(con, null, rs);
        }
        return schemaExists;
    }

    /**
     * Starts the <i>batch mode</i>. If an {@link SQLException} is thrown, then the batch mode is not started. <p/>
     * <strong>Important:</strong> clients that call this method must make sure that
     * {@link #endBatch(boolean)} is called eventually.
     * 
     * @throws SQLException on error
     */
    public final void startBatch() throws SQLException {
        if (inBatchMode()) {
            throw new IllegalStateException("already in batch mode");
        }
        Connection batchConnection = null;
        try {
            batchConnection = getConnection();
            batchConnection.setAutoCommit(false);
            batchConnectionTl.set(batchConnection);
        } catch (SQLException e) {
            // Strive for failure atomicity
            if (batchConnection != null) {
                DbUtility.close(batchConnection, null, null);
            }
            batchConnectionTl.remove();
            throw e;
        }
    }

    /**
     * This method always ends the <i>batch mode</i>.
     * 
     * @param commit whether the changes in the batch should be committed or rolled back
     * @throws SQLException if the commit or rollback of the underlying JDBC Connection threw an {@code
     *             SQLException}
     */
    public final void endBatch(boolean commit) throws SQLException {
        if (!inBatchMode()) {
            throw new IllegalStateException("not in batch mode");
        }
        try {
            if (commit) {
                batchConnectionTl.get().commit();
            } else {
                batchConnectionTl.get().rollback();
            }
        } finally {
            DbUtility.close(batchConnectionTl.get(), null, null);
            batchConnectionTl.set(null);
        }
    }

    /**
     * Executes a general SQL statement and immediately closes all resources.
     * 
     * Note: We use a Statement if there are no parameters to avoid a problem on
     * the Oracle 10g JDBC driver w.r.t. :NEW and :OLD keywords that triggers ORA-17041.
     *
     * @param sql an SQL statement string
     * @param params the parameters for the SQL statement
     * @throws SQLException on error
     */
    public final void exec(final String sql, final Object... params) throws SQLException {
        new RetryManager<Void>() {

            @Override
            protected Void call() throws SQLException {
                reallyExec(sql, params);
                return null;
            }
            
        }.doTry();
    }
    
    private void reallyExec(String sql, Object... params) throws SQLException {
        Connection con = null;
        Statement stmt = null;
        try {
            con = getConnection();
            if (params == null || params.length == 0) {
                stmt = con.createStatement();
                stmt.execute(sql);
            } else {
                stmt = con.prepareStatement(sql);
                execute((PreparedStatement) stmt, params);
            }
        } finally {
            closeResources(con, stmt, null);
        }
    }

    /**
     * Executes an update or delete statement and returns the update count.
     * 
     * @param sql an SQL statement string
     * @param params the parameters for the SQL statement
     * @return the update count
     * @throws SQLException on error
     */
    public final int update(final String sql, final Object[] params) throws SQLException {
        return new RetryManager<Integer>() {

            @Override
            protected Integer call() throws SQLException {
                return reallyUpdate(sql, params);
            }
            
        }.doTry();
    }

    private int reallyUpdate(String sql, Object[] params) throws SQLException {
        Connection con = null;
        PreparedStatement stmt = null;
        try {
            con = getConnection();
            stmt = con.prepareStatement(sql);
            return execute(stmt, params).getUpdateCount();
        } finally {
            closeResources(con, stmt, null);
        }
    }

    /**
     * Executes a general SQL statement and returns the {@link ResultSet} of the executed statement. The
     * returned {@link ResultSet} should be closed by clients.
     * 
     * @param sql an SQL statement string
     * @param params the parameters for the SQL statement
     * @param returnGeneratedKeys whether generated keys should be returned
     * @param maxRows the maximum number of rows in a potential {@link ResultSet} (0 means no limit)
     * @return a {@link ResultSet}
     * @throws SQLException on error
     */
    public final ResultSet exec(final String sql, final Object[] params, final boolean returnGeneratedKeys,
            final int maxRows) throws SQLException {
        return new RetryManager<ResultSet>() {

            @Override
            protected ResultSet call() throws SQLException {
                return reallyExec(sql, params, returnGeneratedKeys, maxRows);
            }
            
        }.doTry();
    }
    
    private ResultSet reallyExec(String sql, Object[] params, boolean returnGeneratedKeys, int maxRows)
            throws SQLException {
        Connection con = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            con = getConnection();
            if (returnGeneratedKeys) {
                stmt = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            } else {
                stmt = con.prepareStatement(sql);
            }
            stmt.setMaxRows(maxRows);
            execute(stmt, params);
            if (returnGeneratedKeys) {
                rs = stmt.getGeneratedKeys();
            } else {
                rs = stmt.getResultSet();
            }
            // Don't wrap null
            if (rs == null) {
                return null;
            }
            if (inBatchMode()) {
                return ResultSetWrapper.newInstance(null, stmt, rs);
            } else {
                return ResultSetWrapper.newInstance(con, stmt, rs);
            }
        } catch (SQLException e) {
            closeResources(con, stmt, rs);
            throw e;
        }
    }

    /**
     * Gets a connection based on the {@code batchMode} state of this helper. The connection should be closed
     * by a call to {@link #closeResources(Connection, Statement, ResultSet)} which also takes the {@code
     * batchMode} state into account.
     * 
     * @return a {@code Connection} to use, based on the batch mode state
     * @throws SQLException on error
     */
    protected final Connection getConnection() throws SQLException {
        if (inBatchMode()) {
            return batchConnectionTl.get();
        } else {
            Connection con = dataSource.getConnection();
            // JCR-1013: Setter may fail unnecessarily on a managed connection
            if (!con.getAutoCommit()) {
                con.setAutoCommit(true);
            }
            return con;
        }
    }

    /**
     * Closes the given resources given the {@code batchMode} state.
     * 
     * @param con the {@code Connection} obtained through the {@link #getConnection()} method
     * @param stmt a {@code Statement}
     * @param rs a {@code ResultSet}
     */
    protected final void closeResources(Connection con, Statement stmt, ResultSet rs) {
        if (inBatchMode()) {
            DbUtility.close(null, stmt, rs);
        } else {
            DbUtility.close(con, stmt, rs);
        }
    }

    /**
     * This method is used by all methods of this class that execute SQL statements. This default
     * implementation sets all parameters and unwraps {@link StreamWrapper} instances. Subclasses may override
     * this method to do something special with the parameters. E.g., the {@link Oracle10R1ConnectionHelper}
     * overrides it in order to add special blob handling.
     * 
     * @param stmt the {@link PreparedStatement} to execute
     * @param params the parameters
     * @return the executed statement
     * @throws SQLException on error
     */
    protected PreparedStatement execute(PreparedStatement stmt, Object[] params) throws SQLException {
        for (int i = 0; params != null && i < params.length; i++) {
            Object p = params[i];
            // FIXME: what about already consumed input streams when in a retry?
            if (p instanceof StreamWrapper) {
                StreamWrapper wrapper = (StreamWrapper) p;
                stmt.setBinaryStream(i + 1, wrapper.getStream(), (int) wrapper.getSize());
            } else {
                stmt.setObject(i + 1, p);
            }
        }
        stmt.execute();
        return stmt;
    }
    
    /**
     * This class encapsulates the logic to retry a method invocation if it threw an SQLException.
     * 
     * @param <T> the return type of the method which is retried if it failed
     */
    public abstract class RetryManager<T> {

        public final T doTry() throws SQLException {
            if (inBatchMode()) {
                return call();
            } else {
                boolean sleepInterrupted = false;
                int failures = 0;
                SQLException lastException = null;
                while (!sleepInterrupted && (blockOnConnectionLoss || failures <= RETRIES)) {
                    try {
                        return call();
                    } catch (SQLException e) {
                        lastException = e;
                    }
                    log.error("Failed to execute SQL (stacktrace on DEBUG log level)", lastException);
                    log.debug("Failed to execute SQL", lastException);
                    failures++;
                    if (blockOnConnectionLoss || failures <= RETRIES) { // if we're going to try again
                        try {
                            Thread.sleep(SLEEP_BETWEEN_RETRIES_MS);
                        } catch (InterruptedException e1) {
                            Thread.currentThread().interrupt();
                            sleepInterrupted = true;
                            log.error("Interrupted: canceling retry");
                        }
                    }
                }
                throw lastException;
            }
        }
        
        protected abstract T call() throws SQLException;
    }
}
