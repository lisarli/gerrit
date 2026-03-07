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
            + "end_char INT64 NOT NULL DEFAULT (0)"
            + ") PRIMARY KEY(change_id, patch_set_id, account_id, file_name, line_number, side, "
            + "start_line, start_char, end_line, end_char)");
  }
}
