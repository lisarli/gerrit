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

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.exceptions.DuplicateKeyException;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.config.ThreadSettingsConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.eclipse.jgit.lib.Config;

/**
 * {@link JdbcAccountPatchLineReviewStore} for H2 (default on-disk site DB or explicit {@code
 * jdbc:h2:...} URL).
 *
 * <p>{@link #convertError} maps SQLSTATE {@code 23001}/{@code 23505} to {@link DuplicateKeyException}
 * so {@link com.google.gerrit.server.change.LineReviewPropagation} can treat a duplicate row as a
 * benign skip when inserting carried-over tentative markers.
 */
@Singleton
public class H2AccountPatchLineReviewStore extends JdbcAccountPatchLineReviewStore {

  @Inject
  H2AccountPatchLineReviewStore(
      @GerritServerConfig Config cfg,
      SitePaths sitePaths,
      ThreadSettingsConfig threadSettingsConfig) {
    super(cfg, sitePaths, threadSettingsConfig);
  }

  @VisibleForTesting
  H2AccountPatchLineReviewStore(DataSource dataSource) {
    super(dataSource);
  }

  @Override
  public StorageException convertError(String op, SQLException err) {
    switch (getSQLStateInt(err)) {
      case 23001, 23505 -> {
        return new DuplicateKeyException("account_patch_line_reviews", err);
      }
      default -> {
        if (err.getCause() == null && err.getNextException() != null) {
          err.initCause(err.getNextException());
        }
        return new StorageException(op + " failure on account_patch_line_reviews", err);
      }
    }
  }
}
