// Copyright (C) 2026 The Android Open Source Project
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.client.ReviewStatus;
import com.google.gerrit.server.CommentsUtil;
import com.google.gerrit.server.change.AccountPatchLineReviewStore.ReviewedLine;
import com.google.gerrit.server.patch.DiffMappings;
import com.google.gerrit.server.patch.DiffNotAvailableException;
import com.google.gerrit.server.patch.DiffOperations;
import com.google.gerrit.server.patch.DiffOptions;
import com.google.gerrit.server.patch.GitPositionTransformer;
import com.google.gerrit.server.patch.GitPositionTransformer.BestPositionOnConflict;
import com.google.gerrit.server.patch.GitPositionTransformer.Mapping;
import com.google.gerrit.server.patch.GitPositionTransformer.Position;
import com.google.gerrit.server.patch.GitPositionTransformer.PositionedEntity;
import com.google.gerrit.server.patch.filediff.FileDiffOutput;
import com.google.gerrit.server.patch.filediff.FileEdits;
import com.google.gerrit.server.patch.filediff.TaggedEdit;
import com.google.gerrit.server.plugincontext.PluginItemContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Carries line-review markers from a prior patch set to a new patch set for regions that still exist
 * in the revision tree, using the same diff-based position mapping as comment porting.
 *
 * <p>Only the revision side of the diff (side {@code 1}) is propagated for now.
 */
@Singleton
public class LineReviewPropagation {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final PluginItemContext<AccountPatchLineReviewStore> lineReviewStore;
  private final DiffOperations diffOperations;
  private final CommentsUtil commentsUtil;
  private final GitPositionTransformer positionTransformer =
      new GitPositionTransformer(BestPositionOnConflict.INSTANCE);

  @Inject
  LineReviewPropagation(
      PluginItemContext<AccountPatchLineReviewStore> lineReviewStore,
      DiffOperations diffOperations,
      CommentsUtil commentsUtil) {
    this.lineReviewStore = lineReviewStore;
    this.diffOperations = diffOperations;
    this.commentsUtil = commentsUtil;
  }

  /**
   * Propagates markers from {@code priorPatchSet} onto {@code newPatchSet} for all accounts. No-op
   * if the line-review store is not configured.
   */
  public void propagateOnNewPatchSet(
      Change change, PatchSet priorPatchSet, PatchSet newPatchSet) {
    lineReviewStore.run(
        store -> {
          try {
            propagate(store, change, priorPatchSet, newPatchSet);
          } catch (Exception e) {
            logger.atWarning().withCause(e).log(
                "Failed to propagate line reviews from patch set %s to %s on change %s",
                priorPatchSet.number(),
                newPatchSet.number(),
                change.getId());
          }
        });
  }

  @VisibleForTesting
  void propagate(
      AccountPatchLineReviewStore store,
      Change change,
      PatchSet priorPatchSet,
      PatchSet newPatchSet)
      throws DiffNotAvailableException {
    if (!priorPatchSet.id().changeId().equals(newPatchSet.id().changeId())) {
      return;
    }
    PatchSet.Id priorId = priorPatchSet.id();
    PatchSet.Id newId = newPatchSet.id();
    // Side 1 = REVISION in stored rows; parent-side markers are not ported.
    short revisionSide = 1;

    ObjectId oldCommit =
        commentsUtil
            .determineCommitId(change, priorPatchSet, revisionSide)
            .orElse(ObjectId.zeroId());
    ObjectId newCommit =
        commentsUtil
            .determineCommitId(change, newPatchSet, revisionSide)
            .orElse(ObjectId.zeroId());
    if (ObjectId.zeroId().equals(oldCommit) || ObjectId.zeroId().equals(newCommit)) {
      return;
    }

    // One mapping per modified file: line/column shifts from oldCommit to newCommit.
    ImmutableSet<Mapping> mappings = loadCommitMappings(change.getProject(), oldCommit, newCommit);

    for (com.google.gerrit.entities.Account.Id accountId : store.accountsWithLineReviews(priorId)) {
      Optional<AccountPatchLineReviewStore.PatchSetWithReviewedLines> priorLines =
          store.findReviewedLines(priorId, accountId, null);
      if (priorLines.isEmpty()) {
        continue;
      }
      ImmutableList<ReviewedLine> revisionLines =
          priorLines.get().lines().stream()
              .filter(l -> l.side() == revisionSide)
              .filter(l -> l.reviewStatus() != ReviewStatus.UNREAD)
              .collect(toImmutableList());
      if (revisionLines.isEmpty()) {
        continue;
      }

      // Only markers that can be mapped onto unchanged/new positions are carried forward. The
      // carried rows are stored as TENTATIVELY_READ so users can quickly spot what was likely
      // already reviewed while still distinguishing it from an explicit READ on this patch set.
      ImmutableList<PositionedEntity<ReviewedLine>> positioned =
          revisionLines.stream().map(this::toPositionedEntity).collect(toImmutableList());
      ImmutableSet<ReviewedLine> propagated =
          positionTransformer.transform(positioned, mappings).stream()
              .map(PositionedEntity::getEntityAtUpdatedPosition)
              .filter(Objects::nonNull)
              .collect(toImmutableSet());

      if (!propagated.isEmpty()) {
        // dedupe avoids duplicate inserts from transform overlap.
        store.insertPropagatedTentativeReviews(newId, accountId, dedupe(propagated));
      }
    }
  }

  /** Collapses multiple {@link ReviewedLine} rows that landed on identical geometry after mapping. */
  private ImmutableList<ReviewedLine> dedupe(ImmutableSet<ReviewedLine> lines) {
    Set<String> seen = new HashSet<>();
    ImmutableList.Builder<ReviewedLine> b = ImmutableList.builder();
    for (ReviewedLine l : lines) {
      String key =
          l.path()
              + "\0"
              + l.lineNumber()
              + "\0"
              + l.side()
              + "\0"
              + l.startLine()
              + "\0"
              + l.startChar()
              + "\0"
              + l.endLine()
              + "\0"
              + l.endChar();
      if (seen.add(key)) {
        b.add(l);
      }
    }
    return b.build();
  }

  /**
   * Builds position mappings between two revision commits so {@link GitPositionTransformer} can
   * move each marker from {@code from} to {@code to}.
   */
  private ImmutableSet<Mapping> loadCommitMappings(
      com.google.gerrit.entities.Project.NameKey project, ObjectId from, ObjectId to)
      throws DiffNotAvailableException {
    Map<String, FileDiffOutput> modifiedFiles =
        diffOperations.listModifiedFiles(
            project,
            from,
            to,
            DiffOptions.builder().skipFilesWithAllEditsDueToRebase(false).build());
    return modifiedFiles.values().stream()
        .map(LineReviewPropagation::getFileEdits)
        .map(DiffMappings::toMapping)
        .collect(toImmutableSet());
  }

  /** Converts unified diff output into the edit list format expected by {@link DiffMappings}. */
  private static FileEdits getFileEdits(FileDiffOutput fileDiffOutput) {
    return FileEdits.create(
        fileDiffOutput.edits().stream().map(TaggedEdit::edit).collect(toImmutableList()),
        fileDiffOutput.oldPath(),
        fileDiffOutput.newPath());
  }

  /**
   * Wraps a stored marker so the transformer can read its old-file position and materialize a new
   * {@link ReviewedLine} via {@link #createReviewedLineAtNewPosition}.
   */
  private PositionedEntity<ReviewedLine> toPositionedEntity(ReviewedLine line) {
    return PositionedEntity.create(
        line,
        LineReviewPropagation::extractPosition,
        LineReviewPropagation::createReviewedLineAtNewPosition);
  }

  /** File path plus 0-based line range (see {@link #extractLineRange}) for the transformer. */
  private static Position extractPosition(ReviewedLine line) {
    Position.Builder positionBuilder = Position.builder();
    positionBuilder.filePath(line.path());
    extractLineRange(line).ifPresent(positionBuilder::lineRange);
    return positionBuilder.build();
  }

  /**
   * Line specifications are 1-based in {@link ReviewedLine}; {@link Position} uses 0-based {@link
   * GitPositionTransformer.Range} with an exclusive end line, matching {@code CommentPorter}.
   */
  private static Optional<GitPositionTransformer.Range> extractLineRange(ReviewedLine line) {
    boolean hasExplicitRange =
        line.startLine() != line.lineNumber()
            || line.endLine() != line.lineNumber()
            || line.startChar() > 0
            || line.endChar() > 0;
    if (hasExplicitRange) {
      // Multi-line or intra-line character span: map to 0-based half-open [start, end) line range.
      // When the region ends with characters on the last line, the exclusive end line stays at
      // endLine; when it ends at end-of-line without trailing chars, the transformer uses one line
      // less for the exclusive end (matches comment porting).
      int exclusiveEndLine =
          line.endChar() > 0 ? line.endLine() : line.endLine() - 1;
      return Optional.of(
          GitPositionTransformer.Range.create(line.startLine() - 1, exclusiveEndLine));
    }
    // Single-line point marker: half-open range [line-1, line) in 0-based indices.
    return Optional.of(
        GitPositionTransformer.Range.create(line.lineNumber() - 1, line.lineNumber()));
  }

  /**
   * Builds the {@link ReviewedLine} row for the new commit after the transformer mapped {@code orig}
   * to {@code newPosition}.
   *
   * <p>Returns {@code null} if the region could not be placed (e.g. deleted hunk); those markers
   * are dropped rather than stored at a bogus line.
   */
  @Nullable
  private static ReviewedLine createReviewedLineAtNewPosition(
      ReviewedLine orig, Position newPosition) {
    if (!newPosition.filePath().isPresent() || !newPosition.lineRange().isPresent()) {
      return null;
    }
    String path = newPosition.filePath().get();
    GitPositionTransformer.Range lr = newPosition.lineRange().get();
    short side = orig.side();
    boolean hasCharRange = orig.startChar() > 0 || orig.endChar() > 0;
    boolean multiLine = orig.startLine() != orig.endLine();
    if (hasCharRange || multiLine) {
      // Geometry: keep the same start/end character columns from the prior patch set so the stored
      // region still describes a sub-line or multi-line span after mapping. Line numbers come from
      // the mapped range (lr); adjustedEndLine reconciles 0-based lr.end() with 1-based ReviewedLine
      // endLine when the original range ended at EOL without trailing characters.
      //
      // Status: always TENTATIVELY_READ. 
      int adjustedEndLine = orig.endChar() > 0 ? lr.end() : lr.end() + 1;
      return ReviewedLine.create(
          path,
          adjustedEndLine,
          side,
          lr.start() + 1,
          orig.startChar(),
          adjustedEndLine,
          orig.endChar(),
          ReviewStatus.TENTATIVELY_READ);
    }
    // Simple single-line marker: anchor line is lr start (1-based); no character span.
    int line1 = lr.start() + 1;
    return ReviewedLine.create(
        path, line1, side, line1, 0, line1, 0, ReviewStatus.TENTATIVELY_READ);
  }
}
