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

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.exceptions.DuplicateKeyException;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.LineReviewAction;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.config.ThreadSettingsConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.UUID;
import org.eclipse.jgit.lib.Config;

@Singleton
public class CloudSpannerAccountPatchLineReviewStore extends JdbcAccountPatchLineReviewStore {

  private static final int ERR_DUP_KEY = 6;

  @Inject
  CloudSpannerAccountPatchLineReviewStore(
      @GerritServerConfig Config cfg,
      SitePaths sitePaths,
      ThreadSettingsConfig threadSettingsConfig) {
    super(cfg, sitePaths, threadSettingsConfig);
  }

  @Override
  public StorageException convertError(String op, SQLException err) {
    return switch (err.getErrorCode()) {
      case ERR_DUP_KEY -> new DuplicateKeyException("ACCOUNT_PATCH_LINE_REVIEWS", err);
      default -> new StorageException(op + " failure on ACCOUNT_PATCH_LINE_REVIEWS", err);
    };
  }

  @Override
  protected void doCreateTable(Statement stmt) throws SQLException {
    stmt.executeUpdate(
        "CREATE TABLE IF NOT EXISTS account_patch_line_reviews ("
            + "account_id INT64 NOT NULL DEFAULT (0),"
            + "change_id INT64 NOT NULL DEFAULT (0),"
            + "patch_set_id INT64 NOT NULL DEFAULT (0),"
            + "file_name STRING(MAX) NOT NULL DEFAULT (''),"
            + "line_number INT64 NOT NULL DEFAULT (1),"
            + "side INT64 NOT NULL DEFAULT (1),"
            + "start_line INT64 NOT NULL DEFAULT (1),"
            + "start_char INT64 NOT NULL DEFAULT (0),"
            + "end_line INT64 NOT NULL DEFAULT (1),"
            + "end_char INT64 NOT NULL DEFAULT (0),"
            + "review_status INT64 NOT NULL DEFAULT (0),"
            + "tentative_carryover BOOL NOT NULL DEFAULT (FALSE)"
            + ") PRIMARY KEY(change_id, patch_set_id, account_id, file_name, line_number, side, "
            + "start_line, start_char, end_line, end_char)");
  }

  @Override
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
                + "(id, account_id, change_id, patch_set_id, file_name, line_number, side, "
                + "start_line, start_char, end_line, end_char, action, created_on) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      stmt.setString(1, UUID.randomUUID().toString());
      stmt.setInt(2, accountId.get());
      stmt.setInt(3, psId.changeId().get());
      stmt.setInt(4, psId.get());
      stmt.setString(5, path);
      stmt.setInt(6, lineNumber);
      stmt.setShort(7, side);
      stmt.setInt(8, startLine);
      stmt.setInt(9, startChar);
      stmt.setInt(10, endLine);
      stmt.setInt(11, endChar);
      stmt.setString(12, action.name());
      stmt.setTimestamp(13, new Timestamp(System.currentTimeMillis()));
      stmt.executeUpdate();
    }
  }

  @Override
  protected void doCreateHistoryTable(Statement stmt) throws SQLException {
    // Spanner does not support auto-increment; use a UUID string as the primary key.
    stmt.executeUpdate(
        "CREATE TABLE IF NOT EXISTS account_patch_line_review_history ("
            + "id STRING(36) NOT NULL,"
            + "account_id INT64 NOT NULL DEFAULT (0),"
            + "change_id INT64 NOT NULL DEFAULT (0),"
            + "patch_set_id INT64 NOT NULL DEFAULT (0),"
            + "file_name STRING(MAX) NOT NULL DEFAULT (''),"
            + "line_number INT64 NOT NULL DEFAULT (1),"
            + "side INT64 NOT NULL DEFAULT (1),"
            + "start_line INT64 NOT NULL DEFAULT (1),"
            + "start_char INT64 NOT NULL DEFAULT (0),"
            + "end_line INT64 NOT NULL DEFAULT (1),"
            + "end_char INT64 NOT NULL DEFAULT (0),"
            + "action STRING(10) NOT NULL DEFAULT ('MARKED'),"
            + "created_on TIMESTAMP NOT NULL"
            + ") PRIMARY KEY(id)");
  }
}
