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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.exceptions.DuplicateKeyException;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.client.Side;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.change.AccountPatchLineReviewStore;
import com.google.gerrit.server.config.ConfigUtil;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.config.ThreadSettingsConfig;
import com.google.common.flogger.FluentLogger;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
  private static final String H2_DB = "h2";
  private static final String URL = "url";

  /** Guice module that binds the JDBC store and registers it as a lifecycle listener. */
  public static class JdbcAccountPatchLineReviewStoreModule extends LifecycleModule {
    // cfg is accepted to match the module installation pattern in Daemon.java and
    // WebAppInitializer.java; reserved for future multi-DB dispatch.
    @SuppressWarnings("unused")
    private final Config cfg;

    public JdbcAccountPatchLineReviewStoreModule(Config cfg) {
      this.cfg = cfg;
    }

    @Override
    protected void configure() {
      // Plain bind — not DynamicItem, not plugin-extensible in v1.
      bind(AccountPatchLineReviewStore.class).to(H2AccountPatchLineReviewStore.class);
      listener().to(H2AccountPatchLineReviewStore.class);
    }
  }

  private final DataSource ds;

  protected JdbcAccountPatchLineReviewStore(
      Config cfg, SitePaths sitePaths, ThreadSettingsConfig threadSettingsConfig) {
    this.ds = createDataSource(cfg, sitePaths, threadSettingsConfig);
  }

  /** Constructor for tests: accepts a pre-configured DataSource directly. */
  @VisibleForTesting
  JdbcAccountPatchLineReviewStore(DataSource ds) {
    this.ds = ds;
  }

  /** Creates an in-memory H2 store for use in unit tests. */
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

  private static String getUrl(Config cfg, SitePaths sitePaths) {
    String url = cfg.getString(ACCOUNT_PATCH_LINE_REVIEW_DB, null, URL);
    if (url == null) {
      return createH2Url(sitePaths.db_dir.resolve("account_patch_line_reviews"));
    }
    return url;
  }

  private static DataSource createDataSource(
      Config cfg, SitePaths sitePaths, ThreadSettingsConfig threadSettingsConfig) {
    BasicDataSource datasource = new BasicDataSource();
    String url = getUrl(cfg, sitePaths);
    int poolLimit = threadSettingsConfig.getDatabasePoolLimit();
    datasource.setUrl(url);
    datasource.setDriverClassName(getDriverFromUrl(url));
    datasource.setMaxActive(cfg.getInt(ACCOUNT_PATCH_LINE_REVIEW_DB, "poolLimit", poolLimit));
    datasource.setMinIdle(cfg.getInt(ACCOUNT_PATCH_LINE_REVIEW_DB, "poolminidle", 4));
    datasource.setMaxIdle(
        cfg.getInt(
            ACCOUNT_PATCH_LINE_REVIEW_DB, "poolmaxidle", Math.min(poolLimit, 16)));
    datasource.setInitialSize(datasource.getMinIdle());
    datasource.setMaxWait(
        ConfigUtil.getTimeUnit(
            cfg,
            ACCOUNT_PATCH_LINE_REVIEW_DB,
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
    if (url.contains(H2_DB)) {
      return "org.h2.Driver";
    }
    // For v1, only H2 is supported. More drivers can be added in future sprints.
    throw new IllegalArgumentException(
        "unsupported driver type for account patch line reviews db: " + url);
  }

  private static String createH2Url(Path path) {
    return "jdbc:h2:" + path.toUri().toString();
  }

  @Override
  public void start() {
    try {
      createTableIfNotExists();
    } catch (StorageException e) {
      logger.atSevere().withCause(e).log(
          "Failed to create table to store account patch line reviews");
    }
  }

  @Override
  public void stop() {}

  public void createTableIfNotExists() {
    try (Connection con = ds.getConnection();
        Statement stmt = con.createStatement()) {
      stmt.executeUpdate(
          "CREATE TABLE IF NOT EXISTS account_patch_line_reviews ("
              + "account_id   INTEGER      DEFAULT 0  NOT NULL, "
              + "change_id    INTEGER      DEFAULT 0  NOT NULL, "
              + "patch_set_id INTEGER      DEFAULT 0  NOT NULL, "
              + "file_name    VARCHAR(4096) DEFAULT '' NOT NULL, "
              + "side         SMALLINT     DEFAULT 1  NOT NULL, "
              + "line_nbr     INTEGER      DEFAULT 1  NOT NULL, "
              + "CONSTRAINT pk_account_patch_line_reviews "
              + "PRIMARY KEY (change_id, patch_set_id, account_id, file_name, side, line_nbr)"
              + ")");
    } catch (SQLException e) {
      throw convertError("create", e);
    }
  }

  @VisibleForTesting
  public void dropTableIfExists() {
    try (Connection con = ds.getConnection();
        Statement stmt = con.createStatement()) {
      stmt.executeUpdate("DROP TABLE IF EXISTS account_patch_line_reviews");
    } catch (SQLException e) {
      throw convertError("drop", e);
    }
  }

  // Side encoding: REVISION → 1, PARENT → 0
  private static short sideToShort(Side side) {
    return side == Side.REVISION ? (short) 1 : (short) 0;
  }

  private static Side shortToSide(short s) {
    return s == 1 ? Side.REVISION : Side.PARENT;
  }

  @Override
  public void markReviewedLine(
      PatchSet.Id psId, Account.Id accountId, String path, Side side, int line) {
    try (Connection con = ds.getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                "INSERT INTO account_patch_line_reviews "
                    + "(account_id, change_id, patch_set_id, file_name, side, line_nbr) "
                    + "VALUES (?, ?, ?, ?, ?, ?)")) {
      stmt.setInt(1, accountId.get());
      stmt.setInt(2, psId.changeId().get());
      stmt.setInt(3, psId.get());
      stmt.setString(4, path);
      stmt.setShort(5, sideToShort(side));
      stmt.setInt(6, line);
      stmt.executeUpdate();
    } catch (SQLException e) {
      StorageException ex = convertError("insert", e);
      if (ex instanceof DuplicateKeyException) {
        // Row already exists — idempotent, ignore.
        return;
      }
      throw ex;
    }
  }

  @Override
  public void clearReviewedLine(
      PatchSet.Id psId, Account.Id accountId, String path, Side side, int line) {
    try (Connection con = ds.getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                "DELETE FROM account_patch_line_reviews "
                    + "WHERE account_id = ? AND change_id = ? AND patch_set_id = ? "
                    + "AND file_name = ? AND side = ? AND line_nbr = ?")) {
      stmt.setInt(1, accountId.get());
      stmt.setInt(2, psId.changeId().get());
      stmt.setInt(3, psId.get());
      stmt.setString(4, path);
      stmt.setShort(5, sideToShort(side));
      stmt.setInt(6, line);
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw convertError("delete", e);
    }
  }

  @Override
  public ImmutableList<LineMarker> listReviewedLines(
      PatchSet.Id psId, Account.Id accountId, String path) {
    try (Connection con = ds.getConnection();
        PreparedStatement stmt =
            con.prepareStatement(
                "SELECT side, line_nbr FROM account_patch_line_reviews "
                    + "WHERE account_id = ? AND change_id = ? AND patch_set_id = ? "
                    + "AND file_name = ?")) {
      stmt.setInt(1, accountId.get());
      stmt.setInt(2, psId.changeId().get());
      stmt.setInt(3, psId.get());
      stmt.setString(4, path);
      try (ResultSet rs = stmt.executeQuery()) {
        List<LineMarker> markers = new ArrayList<>();
        while (rs.next()) {
          markers.add(LineMarker.create(shortToSide(rs.getShort("side")), rs.getInt("line_nbr")));
        }
        return ImmutableList.copyOf(markers);
      }
    } catch (SQLException e) {
      throw convertError("select", e);
    }
  }

  public abstract StorageException convertError(String op, SQLException err);

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
