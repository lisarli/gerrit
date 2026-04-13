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

import com.google.gerrit.exceptions.DuplicateKeyException;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.config.ThreadSettingsConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.sql.SQLException;
import java.sql.Statement;
import org.eclipse.jgit.lib.Config;

@Singleton
public class MysqlAccountPatchLineReviewStore extends JdbcAccountPatchLineReviewStore {

  @Inject
  MysqlAccountPatchLineReviewStore(
      @GerritServerConfig Config cfg,
      SitePaths sitePaths,
      ThreadSettingsConfig threadSettingsConfig) {
    super(cfg, sitePaths, threadSettingsConfig);
  }

  @Override
  public StorageException convertError(String op, SQLException err) {
    switch (err.getErrorCode()) {
      case 1022, 1062, 1169 -> {
        return new DuplicateKeyException("ACCOUNT_PATCH_LINE_REVIEWS", err);
      }
      default -> {
        if (err.getCause() == null && err.getNextException() != null) {
          err.initCause(err.getNextException());
        }
        return new StorageException(op + " failure on ACCOUNT_PATCH_LINE_REVIEWS", err);
      }
    }
  }

  @Override
  protected void doCreateTable(Statement stmt) throws SQLException {
    stmt.executeUpdate(
        "CREATE TABLE IF NOT EXISTS account_patch_line_reviews ("
            + "account_id INTEGER DEFAULT 0 NOT NULL, "
            + "change_id INTEGER DEFAULT 0 NOT NULL, "
            + "patch_set_id INTEGER DEFAULT 0 NOT NULL, "
            + "file_name VARCHAR(255) DEFAULT '' NOT NULL, "
            + "line_number INTEGER DEFAULT 1 NOT NULL, "
            + "side SMALLINT DEFAULT 1 NOT NULL, "
            + "start_line INTEGER DEFAULT 1 NOT NULL, "
            + "start_char INTEGER DEFAULT 0 NOT NULL, "
            + "end_line INTEGER DEFAULT 1 NOT NULL, "
            + "end_char INTEGER DEFAULT 0 NOT NULL, "
            + "review_status SMALLINT DEFAULT 0 NOT NULL, "
            + "tentative_carryover BOOLEAN DEFAULT FALSE NOT NULL, "
            + "CONSTRAINT primary_key_account_patch_line_reviews "
            + "PRIMARY KEY (change_id, patch_set_id, account_id, file_name, line_number, side, "
            + "start_line, start_char, end_line, end_char)"
            + ")");
  }
}
