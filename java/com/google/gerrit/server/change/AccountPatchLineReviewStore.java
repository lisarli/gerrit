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

package com.google.gerrit.server.change;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.client.Side;

/**
 * Store for line-level read markers on patch set files.
 *
 * <p>A line marker is a tuple of (patch set ID, account ID, file path, side, line number) and
 * records whether the user has marked a specific line as read. Only READ rows are stored; the
 * absence of a row implies UNREAD.
 */
public interface AccountPatchLineReviewStore {

  /** A single line-level read marker. */
  @AutoValue
  abstract class LineMarker {
    public abstract Side side();

    public abstract int line();

    public static LineMarker create(Side side, int line) {
      return new AutoValue_AccountPatchLineReviewStore_LineMarker(side, line);
    }
  }

  /**
   * Marks the given line as read. Idempotent: calling this when a row already exists is a no-op.
   *
   * @param psId patch set ID
   * @param accountId account ID of the user
   * @param path file path
   * @param side which side of the diff (REVISION or PARENT)
   * @param line 1-indexed line number
   */
  void markReviewedLine(
      PatchSet.Id psId, Account.Id accountId, String path, Side side, int line);

  /**
   * Removes the read marker for the given line. No-op if no row exists.
   *
   * @param psId patch set ID
   * @param accountId account ID of the user
   * @param path file path
   * @param side which side of the diff (REVISION or PARENT)
   * @param line 1-indexed line number
   */
  void clearReviewedLine(
      PatchSet.Id psId, Account.Id accountId, String path, Side side, int line);

  /**
   * Returns all read-marked lines for the given (psId, accountId, file path).
   *
   * @param psId patch set ID
   * @param accountId account ID of the user
   * @param path file path
   * @return immutable list of line markers (may be empty)
   */
  ImmutableList<LineMarker> listReviewedLines(
      PatchSet.Id psId, Account.Id accountId, String path);
}
