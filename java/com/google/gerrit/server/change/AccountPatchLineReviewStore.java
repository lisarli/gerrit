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
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.api.changes.LineReviewedInput;
import com.google.gerrit.extensions.client.ReviewStatus;
import com.google.gerrit.extensions.client.Side;
import com.google.gerrit.extensions.restapi.NotImplementedException;
import java.util.Collection;
import java.util.Optional;

/**
 * Store for line-level and region-level reviewed flags on changes.
 *
 * <p>A line/region reviewed flag records that a user has reviewed a specific line or character
 * range within a file in a patch set. Attributes mirror comments: file path, line number (1-based),
 * optional range (startLine, startChar, endLine, endChar), and side (PARENT/REVISION).
 *
 * <p>For cluster setups with multiple primary nodes the store must replicate the data between the
 * primary servers.
 */
public interface AccountPatchLineReviewStore {

  /** Describes a single reviewed line or region within a file. */
  @AutoValue
  abstract class ReviewedLine {
    public abstract String path();

    /** 1-based line number. */
    public abstract int lineNumber();

    /** Side: 0 = PARENT, 1 = REVISION. */
    public abstract short side();

    /** Range (startLine, startChar, endLine, endChar). For line-only, startLine==endLine==lineNumber. */
    public abstract int startLine();

    public abstract int startChar();

    public abstract int endLine();

    public abstract int endChar();

    /** Stored status; {@link ReviewStatus#UNREAD} does not appear. */
    public abstract ReviewStatus reviewStatus();

    public static ReviewedLine create(
        String path,
        int lineNumber,
        short side,
        int startLine,
        int startChar,
        int endLine,
        int endChar,
        ReviewStatus reviewStatus) {
      return new AutoValue_AccountPatchLineReviewStore_ReviewedLine(
          path, lineNumber, side, startLine, startChar, endLine, endChar, reviewStatus);
    }

    public Side getSide() {
      Side s = Side.fromShort(side());
      return s != null ? s : Side.REVISION;
    }
  }

  /** Patch set with the list of reviewed lines/regions for a user. */
  @AutoValue
  abstract class PatchSetWithReviewedLines {
    public abstract PatchSet.Id patchSetId();

    public abstract ImmutableList<ReviewedLine> lines();

    public static PatchSetWithReviewedLines create(PatchSet.Id id, ImmutableList<ReviewedLine> lines) {
      return new AutoValue_AccountPatchLineReviewStore_PatchSetWithReviewedLines(id, lines);
    }
  }

  /**
   * Marks the given line or region in the given file and patch set as reviewed by the given user.
   *
   * @param psId patch set ID
   * @param accountId account ID of the user
   * @param path file path
   * @param input line number, optional range, and side
   * @return {@code true} if the flag was updated, {@code false} if it was already set
   */
  boolean markLineReviewed(
      PatchSet.Id psId, Account.Id accountId, String path, LineReviewedInput input);

  /**
   * Marks the given lines/regions as reviewed by the given user.
   *
   * @param psId patch set ID
   * @param accountId account ID of the user
   * @param path file path
   * @param inputs list of line/region inputs
   */
  void markLineReviewed(
      PatchSet.Id psId,
      Account.Id accountId,
      String path,
      Collection<LineReviewedInput> inputs);

  /**
   * Clears the reviewed flag for the given line/region.
   *
   * @param psId patch set ID
   * @param accountId account ID of the user
   * @param path file path
   * @param input line number, optional range, and side (must match the stored entry)
   */
  void clearLineReviewed(
      PatchSet.Id psId, Account.Id accountId, String path, LineReviewedInput input);

  /**
   * Clears all line/region reviewed flags for the given patch set for all users.
   *
   * @param psId patch set ID
   */
  void clearLineReviewed(PatchSet.Id psId);

  /**
   * Clears all line/region reviewed flags for all patch sets in the given change for all users.
   *
   * @param changeId change ID
   */
  void clearLineReviewed(Change.Id changeId);

  /**
   * Clears all line/region reviewed flags for the given user.
   *
   * @param accountId account ID of the user
   */
  default void clearLineReviewedBy(Account.Id accountId) {
    throw new NotImplementedException(
        "clearLineReviewedBy() is not implemented for this AccountPatchLineReviewStore.");
  }

  /**
   * Finds all reviewed lines/regions for the given patch set and user (for the given file, or all
   * files if path is null).
   *
   * @param psId patch set ID
   * @param accountId account ID of the user
   * @param path optional file path to filter by, or null for all files
   * @return list of reviewed lines/regions
   */
  Optional<PatchSetWithReviewedLines> findReviewedLines(
      PatchSet.Id psId, Account.Id accountId, String path);

  /**
   * Accounts that have at least one line review row stored for the given patch set.
   *
   * @param psId patch set ID
   */
  default ImmutableSet<Account.Id> accountsWithLineReviews(PatchSet.Id psId) {
    throw new NotImplementedException(
        "accountsWithLineReviews() is not implemented for this AccountPatchLineReviewStore.");
  }

  /**
   * Inserts {@link ReviewStatus#TENTATIVELY_READ} rows with {@code tentative_carryover = true} for
   * markers carried from a prior patch set by {@link LineReviewPropagation}. Skips a row if the
   * primary key already exists.
   *
   * @param psId new patch set ID
   * @param accountId account ID
   * @param lines geometry and side for each marker; {@link ReviewedLine#reviewStatus()} is ignored
   */
  default void insertPropagatedTentativeReviews(
      PatchSet.Id psId, Account.Id accountId, Collection<ReviewedLine> lines) {
    throw new NotImplementedException(
        "insertPropagatedTentativeReviews() is not implemented for this"
            + " AccountPatchLineReviewStore.");
  }
}
