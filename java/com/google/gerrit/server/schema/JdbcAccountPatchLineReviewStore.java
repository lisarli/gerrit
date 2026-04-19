// Copyright (C) 2025 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.schema;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.primitives.Ints;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.exceptions.DuplicateKeyException;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.changes.LineReviewedInput;
import com.google.gerrit.extensions.client.Side;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.change.AccountPatchLineReviewStore;
import com.google.gerrit.server.config.ConfigUtil;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.config.ThreadSettingsConfig;
import com.google.gerrit.server.logging.Metadata;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.logging.TraceContext.TraceTimer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import com.google.common.annotations.VisibleForTesting;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.commons.dbcp.BasicDataSource;
import org.eclipse.jgit.lib.Config;

public abstract class JdbcAccountPatchLineReviewStore
    implements AccountPatchLineReviewStore, LifecycleListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @VisibleForTesting
  public static final String TEST_IN_MEMORY_URL =
      "jdbc:h2:mem:account_patch_line_reviews;DB_CLOSE_DELAY=-1";
  private static final String ACCOUNT_PATCH_LINE_REVIEW_DB = "accountPatchLineReviewDb";
  private static final String ACCOUNT_PATCH_REVIEW_DB = "accountPatchReviewDb";
  private static final String H2_DB = "h2";
  private static final String MARIADB = "mariadb";
  private static final String MYSQL = "mysql";
  private static final String POSTGRESQL = "postgresql";
  private static final String CLOUDSPANNER = "cloudspanner";
  private static final String URL = "url";

  public static class JdbcAccountPatchLineReviewStoreModule extends LifecycleModule {
    private final Config cfg;

    public JdbcAccountPatchLineReviewStoreModule(Config cfg) {
      this.cfg = cfg;
    }

    @Override
    protected void configure() {
      Class<? extends JdbcAccountPatchLineReviewStore> impl;
      String url = cfg.getString(ACCOUNT_PATCH_LINE_REVIEW_DB, null, URL);
      if (url == null) {
        url = cfg.getString(ACCOUNT_PATCH_REVIEW_DB, null, URL);
      }
      if (url == null || url.contains(H2_DB)) {
        impl = H2AccountPatchLineReviewStore.class;
      } else if (url.contains(POSTGRESQL)) {
        impl = PostgresqlAccountPatchLineReviewStore.class;
      } else if (url.contains(MYSQL)) {
        impl = MysqlAccountPatchLineReviewStore.class;
      } else if (url.contains(MARIADB)) {
        impl = MariaDBAccountPatchLineReviewStore.class;
      } else if (url.contains(CLOUDSPANNER)) {
        impl = CloudSpannerAccountPatchLineReviewStore.class;
      } else {
        throw new IllegalArgumentException(
            "unsupported driver type for account patch line review db: " + url);
      }
      DynamicItem.bind(binder(), AccountPatchLineReviewStore.class).to(impl);
      listener().to(impl);
    }
  }

  private DataSource ds;

  protected JdbcAccountPatchLineReviewStore(
      Config cfg, SitePaths sitePaths, ThreadSettingsConfig threadSettingsConfig) {
    this.ds = createDataSource(cfg, sitePaths, threadSettingsConfig);
  }

  @VisibleForTesting
  protected JdbcAccountPatchLineReviewStore(DataSource dataSource) {
    this.ds = dataSource;
  }

  @VisibleForTesting
  public static H2AccountPatchLineReviewStore createInMemoryForTesting() {
    BasicDataSource ds = new BasicDataSource();
    ds.setUrl(TEST_IN_MEMORY_URL);
    ds.setDriverClassName("org.h2.Driver");
    ds.setMaxActive(4);
    ds.setMinIdle(1);
    ds.setMaxIdle(4);
    ds.setInitialSize(1);
    return new H2AccountPatchLineReviewStore(ds);
  }

  private static String getUrl(@GerritServerConfig Config cfg, SitePaths sitePaths) {
    String url = cfg.getString(ACCOUNT_PATCH_LINE_REVIEW_DB, null, URL);
    if (url == null) {
      url = cfg.getString(ACCOUNT_PATCH_REVIEW_DB, null, URL);
    }
    if (url == null) {
      return createH2Url(sitePaths.db_dir.resolve("account_patch_reviews"));
    }
    return url;
  }

  private static DataSource createDataSource(
      Config cfg, SitePaths sitePaths, ThreadSettingsConfig threadSettingsConfig) {
    BasicDataSource datasource = new BasicDataSource();
    String url = getUrl(cfg, sitePaths);
    String configSection =
        cfg.getString(ACCOUNT_PATCH_LINE_REVIEW_DB, null, URL) != null
            ? ACCOUNT_PATCH_LINE_REVIEW_DB
            : ACCOUNT_PATCH_REVIEW_DB;
    int poolLimit = threadSettingsConfig.getDatabasePoolLimit();
    datasource.setUrl(url);
    datasource.setDriverClassName(getDriverFromUrl(url));
    datasource.setMaxActive(cfg.getInt(configSection, "poolLimit", poolLimit));
    datasource.setMinIdle(cfg.getInt(configSection, "poolminidle", 4));
    datasource.setMaxIdle(
        cfg.getInt(configSection, "poolmaxidle", Math.min(poolLimit, 16)));
    datasource.setInitialSize(datasource.getMinIdle());
    datasource.setMaxWait(
        ConfigUtil.getTimeUnit(
            cfg,
            configSection,
            null,
            "poolmaxwait",
            MILLISECONDS.convert(30, SECONDS),
            MILLISECONDS));
    long evictIdleTimeMs = 1000L * 60;
    datasource.setMinEvictableIdleTimeMillis(evictIdleTimeMs);
    datasource.setTimeBetweenEvictionRunsMillis(evictIdleTimeMs / 2);
    return datasource;
  }

  private static String getDriverFromUrl(String url) {
    if (url.contains(POSTGRESQL)) {
      return "org.postgresql.Driver";
    }
    if (url.contains(MYSQL)) {
      return "com.mysql.jdbc.Driver";
    }
    if (url.contains(MARIADB)) {
      return "org.mariadb.jdbc.Driver";
    }
    if (url.contains(CLOUDSPANNER)) {
      return "com.google.cloud.spanner.jdbc.JdbcDriver";
    }
    return "org.h2.Driver";
  }

  private static String createH2Url(Path path) {
    return "jdbc:h2:" + path.toUri().toString();
  }

  protected static short sideToShort(Side side) {
    return side == Side.PARENT ? (short) 0 : (short) 1;
  }

  /** Normalize input to (lineNumber, startLine, startChar, endLine, endChar). */
  protected static void normalizeInput(
      LineReviewedInput input,
      int[] lineNumber,
      int[] startLine,
      int[] startChar,
      int[] endLine,
      int[] endChar) {
    int line = input.line != null && input.line > 0 ? input.line : 1;
    lineNumber[0] = line;
    if (input.range != null
        && input.range.startLine > 0
        && input.range.endLine > 0
        && input.range.startLine <= input.range.endLine) {
      startLine[0] = input.range.startLine;
      startChar[0] = Math.max(0, input.range.startCharacter);
      endLine[0] = input.range.endLine;
      endChar[0] = Math.max(0, input.range.endCharacter);
    } else {
      startLine[0] = line;
      startChar[0] = 0;
      endLine[0] = line;
      endChar[0] = 0;
    }
  }

  @Override
  public void start() {
    try {
      createTableIfNotExists();
    } catch (StorageException e) {
      logger
          .atSevere()
          .withCause(e)
          .log("Failed to create table to store account patch line reviews");
    }
    try {
      createHistoryTableIfNotExists();
    } catch (StorageException e) {
      logger
          .atSevere()
          .withCause(e)
          .log("Failed to create table to store account patch line review history");
    }
  }

  public Connection getConnection() throws SQLException {
    return ds.getConnection();
  }

  public void createTableIfNotExists() {
    try (Connection con = ds.getConnection();
        Statement stmt = con.createStatement()) {
      doCreateTable(stmt);
    } catch (SQLException e) {
      throw convertError("create", e);
    }
  }

  public void createHistoryTableIfNotExists() {
    try (Connection con = ds.getConnection();
        Statement stmt = con.createStatement()) {
      doCreateHistoryTable(stmt);
    } catch (SQLException e) {
      throw convertError("create", e);
    }
  }

  protected void doCreateHistoryTable(Statement stmt) throws SQLException {
    stmt.executeUpdate(
        "CREATE TABLE IF NOT EXISTS account_patch_line_review_history ("
            + "id BIGINT GENERATED ALWAYS AS IDENTITY, "
            + "account_id INTEGER DEFAULT 0 NOT NULL, "
            + "change_id INTEGER DEFAULT 0 NOT NULL, "
            + "patch_set_id INTEGER DEFAULT 0 NOT NULL, "
            + "file_name VARCHAR(4096) DEFAULT '' NOT NULL, "
            + "line_number INTEGER DEFAULT 1 NOT NULL, "
            + "side SMALLINT DEFAULT 1 NOT NULL, "
            + "start_line INTEGER DEFAULT 1 NOT NULL, "
            + "start_char INTEGER DEFAULT 0 NOT NULL, "
            + "end_line INTEGER DEFAULT 1 NOT NULL, "
            + "end_char INTEGER DEFAULT 0 NOT NULL, "
            + "action VARCHAR(10) DEFAULT 'MARKED' NOT NULL, "
            + "created_on TIMESTAMP NOT NULL"
            + ")");
  }

  protected void doCreateTable(Statement stmt) throws SQLException {
    stmt.executeUpdate(
        "CREATE TABLE IF NOT EXISTS account_patch_line_reviews ("
            + "account_id INTEGER DEFAULT 0 NOT NULL, "
            + "change_id INTEGER DEFAULT 0 NOT NULL, "
            + "patch_set_id INTEGER DEFAULT 0 NOT NULL, "
            + "file_name VARCHAR(4096) DEFAULT '' NOT NULL, "
            + "line_number INTEGER DEFAULT 1 NOT NULL, "
            + "side SMALLINT DEFAULT 1 NOT NULL, "
            + "start_line INTEGER DEFAULT 1 NOT NULL, "
            + "start_char INTEGER DEFAULT 0 NOT NULL, "
            + "end_line INTEGER DEFAULT 1 NOT NULL, "
            + "end_char INTEGER DEFAULT 0 NOT NULL, "
            + "CONSTRAINT primary_key_account_patch_line_reviews "
            + "PRIMARY KEY (change_id, patch_set_id, account_id, file_name, line_number, side, "
            + "start_line, start_char, end_line, end_char)"
            + ")");
  }

  @Override
  public void stop() {}

  @Override
  public boolean markLineReviewed(
      PatchSet.Id psId, Account.Id accountId, String path, LineReviewedInput input) {
    Side side = input.side != null ? input.side : Side.REVISION;
    int[] lineNumber = new int[1];
    int[] startLine = new int[1], startChar = new int[1], endLine = new int[1], endChar = new int[1];
    normalizeInput(input, lineNumber, startLine, startChar, endLine, endChar);

    try (TraceTimer ignored =
            TraceContext.newTimer(
                "Mark line/region as reviewed",
                Metadata.builder()
                    .patchSetId(psId.get())
                    .accountId(accountId.get())
                    .filePath(path)
                    .build());
        Connection con = ds.getConnection()) {
      try (PreparedStatement stmt =
          con.prepareStatement(
              "INSERT INTO account_patch_line_reviews "
                  + "(account_id, change_id, patch_set_id, file_name, line_number, side, "
                  + "start_line, start_char, end_line, end_char) VALUES "
                  + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        stmt.setInt(1, accountId.get());
        stmt.setInt(2, psId.changeId().get());
        stmt.setInt(3, psId.get());
        stmt.setString(4, path);
        stmt.setInt(5, lineNumber[0]);
        stmt.setShort(6, sideToShort(side));
        stmt.setInt(7, startLine[0]);
        stmt.setInt(8, startChar[0]);
        stmt.setInt(9, endLine[0]);
        stmt.setInt(10, endChar[0]);
        stmt.executeUpdate();
      } catch (SQLException e) {
        StorageException ormException = convertError("insert", e);
        if (ormException instanceof DuplicateKeyException) {
          return false; // already marked — do not log
        }
        throw ormException;
      }
      // Log the mark action (best-effort: don't fail the main operation on history write failure)
      try {
        insertHistoryEntry(
            con, psId, accountId, path, lineNumber[0],
            sideToShort(side), startLine[0], startChar[0], endLine[0], endChar[0],
            LineReviewAction.MARKED);
      } catch (SQLException e) {
        logger.atWarning().withCause(e).log("Failed to log MARKED action to history");
      }
      return true;
    } catch (SQLException e) {
      throw convertError("insert", e);
    }
  }

  @Override
  public void markLineReviewed(
      PatchSet.Id psId,
      Account.Id accountId,
      String path,
      Collection<LineReviewedInput> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      return;
    }
    for (LineReviewedInput input : inputs) {
      var unused = markLineReviewed(psId, accountId, path, input);
    }
  }

  @Override
  public void clearLineReviewed(
      PatchSet.Id psId, Account.Id accountId, String path, LineReviewedInput input) {
    Side side = input.side != null ? input.side : Side.REVISION;
    int[] lineNumber = new int[1];
    int[] startLine = new int[1], startChar = new int[1], endLine = new int[1], endChar = new int[1];
    normalizeInput(input, lineNumber, startLine, startChar, endLine, endChar);

    try (TraceTimer ignored =
            TraceContext.newTimer(
                "Clear line/region reviewed flag",
                Metadata.builder()
                    .patchSetId(psId.get())
                    .accountId(accountId.get())
                    .filePath(path)
                    .build());
        Connection con = ds.getConnection()) {
      int affected;
      try (PreparedStatement stmt =
          con.prepareStatement(
              "DELETE FROM account_patch_line_reviews WHERE account_id = ? AND change_id = ? "
                  + "AND patch_set_id = ? AND file_name = ? AND line_number = ? AND side = ? "
                  + "AND start_line = ? AND start_char = ? AND end_line = ? AND end_char = ?")) {
        stmt.setInt(1, accountId.get());
        stmt.setInt(2, psId.changeId().get());
        stmt.setInt(3, psId.get());
        stmt.setString(4, path);
        stmt.setInt(5, lineNumber[0]);
        stmt.setShort(6, sideToShort(side));
        stmt.setInt(7, startLine[0]);
        stmt.setInt(8, startChar[0]);
        stmt.setInt(9, endLine[0]);
        stmt.setInt(10, endChar[0]);
        affected = stmt.executeUpdate();
      }
      // Only log if a row was actually deleted (don't log no-ops)
      if (affected > 0) {
        try {
          insertHistoryEntry(
              con, psId, accountId, path, lineNumber[0],
              sideToShort(side), startLine[0], startChar[0], endLine[0], endChar[0],
              LineReviewAction.UNMARKED);
        } catch (SQLException e) {
          logger.atWarning().withCause(e).log("Failed to log UNMARKED action to history");
        }
      }
    } catch (SQLException e) {
      throw convertError("delete", e);
    }
  }

  @Override
  public void clearLineReviewed(PatchSet.Id psId) {
    try (TraceTimer ignored =
            TraceContext.newTimer(
                "Clear all line reviewed flags of patch set",
                Metadata.builder().patchSetId(psId.get()).build());
        Connection con = ds.getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                "DELETE FROM account_patch_line_reviews WHERE change_id = ? AND patch_set_id = ?")) {
      stmt.setInt(1, psId.changeId().get());
      stmt.setInt(2, psId.get());
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw convertError("delete", e);
    }
  }

  @Override
  public void clearLineReviewed(Change.Id changeId) {
    try (TraceTimer ignored =
            TraceContext.newTimer(
                "Clear all line reviewed flags of change",
                Metadata.builder().changeId(changeId.get()).build());
        Connection con = ds.getConnection();
        PreparedStatement stmt =
            con.prepareStatement("DELETE FROM account_patch_line_reviews WHERE change_id = ?")) {
      stmt.setInt(1, changeId.get());
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw convertError("delete", e);
    }
  }

  @Override
  public void clearLineReviewedBy(Account.Id accountId) {
    try (TraceTimer ignored =
            TraceContext.newTimer(
                "Clear all line reviewed flags of account",
                Metadata.builder().accountId(accountId.get()).build());
        Connection con = ds.getConnection();
        PreparedStatement stmt =
            con.prepareStatement("DELETE FROM account_patch_line_reviews WHERE account_id = ?")) {
      stmt.setInt(1, accountId.get());
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw convertError("delete", e);
    }
  }

  @Override
  public Optional<PatchSetWithReviewedLines> findReviewedLines(
      PatchSet.Id psId, Account.Id accountId, String path) {
    try (TraceTimer ignored =
            TraceContext.newTimer(
                "Find line reviewed flags",
                Metadata.builder()
                    .patchSetId(psId.get())
                    .accountId(accountId.get())
                    .build());
        Connection con = ds.getConnection()) {
      String sql =
          "SELECT file_name, line_number, side, start_line, start_char, end_line, end_char "
              + "FROM account_patch_line_reviews "
              + "WHERE account_id = ? AND change_id = ? AND patch_set_id = ?";
      if (path != null) {
        sql += " AND file_name = ?";
      }
      sql += " ORDER BY file_name, line_number";
      try (PreparedStatement stmt = con.prepareStatement(sql)) {
        stmt.setInt(1, accountId.get());
        stmt.setInt(2, psId.changeId().get());
        stmt.setInt(3, psId.get());
        if (path != null) {
          stmt.setString(4, path);
        }
        try (ResultSet rs = stmt.executeQuery()) {
          ImmutableList.Builder<ReviewedLine> builder = ImmutableList.builder();
          while (rs.next()) {
            builder.add(
                ReviewedLine.create(
                    rs.getString("file_name"),
                    rs.getInt("line_number"),
                    rs.getShort("side"),
                    rs.getInt("start_line"),
                    rs.getInt("start_char"),
                    rs.getInt("end_line"),
                    rs.getInt("end_char")));
          }
          ImmutableList<ReviewedLine> lines = builder.build();
          if (lines.isEmpty()) {
            return Optional.empty();
          }
          return Optional.of(PatchSetWithReviewedLines.create(psId, lines));
        }
      }
    } catch (SQLException e) {
      throw convertError("select", e);
    }
  }

  /** Inserts a single row into the history table on the given already-open connection. */
  protected void insertHistoryEntry(
      Connection con,
      PatchSet.Id psId,
      Account.Id accountId,
      String path,
      int lineNumber,
      short side,
      int startLine,
      int startChar,
      int endLine,
      int endChar,
      LineReviewAction action)
      throws SQLException {
    try (PreparedStatement stmt =
        con.prepareStatement(
            "INSERT INTO account_patch_line_review_history "
                + "(account_id, change_id, patch_set_id, file_name, line_number, side, "
                + "start_line, start_char, end_line, end_char, action, created_on) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      stmt.setInt(1, accountId.get());
      stmt.setInt(2, psId.changeId().get());
      stmt.setInt(3, psId.get());
      stmt.setString(4, path);
      stmt.setInt(5, lineNumber);
      stmt.setShort(6, side);
      stmt.setInt(7, startLine);
      stmt.setInt(8, startChar);
      stmt.setInt(9, endLine);
      stmt.setInt(10, endChar);
      stmt.setString(11, action.name());
      stmt.setTimestamp(12, new Timestamp(System.currentTimeMillis()));
      stmt.executeUpdate();
    }
  }

  @Override
  public void logLineReviewAction(
      PatchSet.Id psId,
      Account.Id accountId,
      String path,
      LineReviewedInput input,
      LineReviewAction action) {
    Side side = input.side != null ? input.side : Side.REVISION;
    int[] lineNumber = new int[1];
    int[] startLine = new int[1], startChar = new int[1], endLine = new int[1], endChar = new int[1];
    normalizeInput(input, lineNumber, startLine, startChar, endLine, endChar);
    try (Connection con = ds.getConnection()) {
      insertHistoryEntry(
          con, psId, accountId, path, lineNumber[0],
          sideToShort(side), startLine[0], startChar[0], endLine[0], endChar[0], action);
    } catch (SQLException e) {
      throw convertError("insert", e);
    }
  }

  @Override
  public ImmutableList<LineReviewHistoryEntry> findLineReviewHistory(
      Change.Id changeId, Account.Id accountId) {
    try (TraceTimer ignored =
            TraceContext.newTimer(
                "Find line review history",
                Metadata.builder()
                    .changeId(changeId.get())
                    .accountId(accountId.get())
                    .build());
        Connection con = ds.getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                "SELECT patch_set_id, file_name, line_number, side, start_line, start_char, "
                    + "end_line, end_char, action, created_on "
                    + "FROM account_patch_line_review_history "
                    + "WHERE change_id = ? AND account_id = ? "
                    + "ORDER BY created_on ASC")) {
      stmt.setInt(1, changeId.get());
      stmt.setInt(2, accountId.get());
      try (ResultSet rs = stmt.executeQuery()) {
        ImmutableList.Builder<LineReviewHistoryEntry> builder = ImmutableList.builder();
        while (rs.next()) {
          PatchSet.Id psId = PatchSet.id(changeId, rs.getInt("patch_set_id"));
          builder.add(
              LineReviewHistoryEntry.create(
                  psId,
                  accountId,
                  rs.getString("file_name"),
                  rs.getInt("line_number"),
                  rs.getShort("side"),
                  rs.getInt("start_line"),
                  rs.getInt("start_char"),
                  rs.getInt("end_line"),
                  rs.getInt("end_char"),
                  LineReviewAction.valueOf(rs.getString("action")),
                  rs.getTimestamp("created_on")));
        }
        return builder.build();
      }
    } catch (SQLException e) {
      throw convertError("select", e);
    }
  }

  @Override
  public ImmutableMap<Account.Id, ImmutableList<ReviewedLine>> findAllReviewedLines(
      PatchSet.Id psId, String path) {
    try (TraceTimer ignored =
            TraceContext.newTimer(
                "Find all reviewers' line reviewed flags",
                Metadata.builder().patchSetId(psId.get()).filePath(path).build());
        Connection con = ds.getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                "SELECT account_id, file_name, line_number, side, start_line, start_char, "
                    + "end_line, end_char "
                    + "FROM account_patch_line_reviews "
                    + "WHERE change_id = ? AND patch_set_id = ? AND file_name = ? "
                    + "ORDER BY account_id, line_number")) {
      stmt.setInt(1, psId.changeId().get());
      stmt.setInt(2, psId.get());
      stmt.setString(3, path);
      try (ResultSet rs = stmt.executeQuery()) {
        Map<Account.Id, ImmutableList.Builder<ReviewedLine>> builders = new LinkedHashMap<>();
        while (rs.next()) {
          Account.Id acctId = Account.id(rs.getInt("account_id"));
          builders
              .computeIfAbsent(acctId, k -> ImmutableList.builder())
              .add(
                  ReviewedLine.create(
                      rs.getString("file_name"),
                      rs.getInt("line_number"),
                      rs.getShort("side"),
                      rs.getInt("start_line"),
                      rs.getInt("start_char"),
                      rs.getInt("end_line"),
                      rs.getInt("end_char")));
        }
        ImmutableMap.Builder<Account.Id, ImmutableList<ReviewedLine>> result =
            ImmutableMap.builder();
        builders.forEach((acctId, b) -> result.put(acctId, b.build()));
        return result.build();
      }
    } catch (SQLException e) {
      throw convertError("select", e);
    }
  }

  public StorageException convertError(String op, SQLException err) {
    if (err.getCause() == null && err.getNextException() != null) {
      err.initCause(err.getNextException());
    }
    return new StorageException(op + " failure on account_patch_line_reviews", err);
  }

  protected static int getSQLStateInt(SQLException err) {
    String s = getSQLState(err);
    if (s != null) {
      Integer i = Ints.tryParse(s);
      return i != null ? i : -1;
    }
    return 0;
  }

  private static String getSQLState(SQLException err) {
    String ec;
    SQLException next = err;
    do {
      ec = next.getSQLState();
      next = next.getNextException();
    } while (ec == null && next != null);
    return ec;
  }
}
