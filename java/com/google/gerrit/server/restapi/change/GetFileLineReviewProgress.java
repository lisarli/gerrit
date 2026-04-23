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

package com.google.gerrit.server.restapi.change;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.common.FileLineReviewProgressInfo;
import com.google.gerrit.extensions.common.ReviewerLineReviewProgressInfo;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.account.AccountLoader;
import com.google.gerrit.server.change.AccountPatchLineReviewStore;
import com.google.gerrit.server.change.FileLineReviewProgressComputer;
import com.google.gerrit.server.change.FileResource;
import com.google.gerrit.server.change.RevisionFileLineCounts;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.plugincontext.PluginItemContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * GET {@code /changes/{id}/revisions/{rev}/files/{file}/line_review_progress}
 *
 * <p>Whole-file line count is the denominator. The response includes overall file progress across
 * all reviewers plus per-reviewer percentages for each account with line-review rows on this patch
 * set.
 */
@Singleton
public class GetFileLineReviewProgress implements RestReadView<FileResource> {
  private final PluginItemContext<AccountPatchLineReviewStore> accountPatchLineReviewStore;
  private final RevisionFileLineCounts revisionFileLineCounts;
  private final AccountLoader.Factory accountLoaderFactory;

  @Inject
  GetFileLineReviewProgress(
      PluginItemContext<AccountPatchLineReviewStore> accountPatchLineReviewStore,
      RevisionFileLineCounts revisionFileLineCounts,
      AccountLoader.Factory accountLoaderFactory) {
    this.accountPatchLineReviewStore = accountPatchLineReviewStore;
    this.revisionFileLineCounts = revisionFileLineCounts;
    this.accountLoaderFactory = accountLoaderFactory;
  }

  @Override
  public Response<FileLineReviewProgressInfo> apply(FileResource rsrc)
      throws IOException, PermissionBackendException {
    var key = rsrc.getPatchKey();
    String path = key.fileName();
    var psId = key.patchSetId();
    var rev = rsrc.getRevision();

    int totalLines =
        revisionFileLineCounts.countLines(rev.getProject(), rev.getPatchSet().commitId(), path);

    ImmutableSet<Account.Id> accountIds =
        accountPatchLineReviewStore.call(s -> s.accountsWithLineReviews(psId));
    ImmutableList<Account.Id> sorted = accountIds.stream().sorted().collect(toImmutableList());

    AccountLoader loader = accountLoaderFactory.create(true);
    List<ReviewerLineReviewProgressInfo> reviewers = new ArrayList<>();
    ImmutableList.Builder<AccountPatchLineReviewStore.ReviewedLine> allReviewedLines =
        ImmutableList.builder();
    for (Account.Id accountId : sorted) {
      Optional<AccountPatchLineReviewStore.PatchSetWithReviewedLines> opt =
          accountPatchLineReviewStore.call(s -> s.findReviewedLines(psId, accountId, path));
      ImmutableList<AccountPatchLineReviewStore.ReviewedLine> lines =
          opt.map(AccountPatchLineReviewStore.PatchSetWithReviewedLines::lines)
              .orElseGet(ImmutableList::of);
      FileLineReviewProgressComputer.Counts counts =
          FileLineReviewProgressComputer.compute(lines, totalLines);
      allReviewedLines.addAll(lines);

      ReviewerLineReviewProgressInfo info = new ReviewerLineReviewProgressInfo();
      info.account = loader.get(accountId);
      fillPercents(info, counts, totalLines);
      reviewers.add(info);
    }
    loader.fill();

    FileLineReviewProgressComputer.Counts overallCounts =
        FileLineReviewProgressComputer.compute(allReviewedLines.build(), totalLines);

    FileLineReviewProgressInfo out = new FileLineReviewProgressInfo();
    out.totalLinesInFile = totalLines;
    fillPercents(out, overallCounts, totalLines);
    out.reviewers = reviewers;
    return Response.ok(out);
  }

  private static void fillPercents(
      ReviewerLineReviewProgressInfo info,
      FileLineReviewProgressComputer.Counts counts,
      int totalLinesInFile) {
    if (totalLinesInFile <= 0) {
      info.percentRead = null;
      info.percentTentativelyRead = null;
      info.percentUnread = null;
      return;
    }
    info.percentRead = 100.0 * counts.readLines / totalLinesInFile;
    info.percentTentativelyRead = 100.0 * counts.tentativelyReadLines / totalLinesInFile;
    info.percentUnread = 100.0 * counts.unreadLines / totalLinesInFile;
  }

  private static void fillPercents(
      FileLineReviewProgressInfo info,
      FileLineReviewProgressComputer.Counts counts,
      int totalLinesInFile) {
    if (totalLinesInFile <= 0) {
      info.percentRead = null;
      info.percentTentativelyRead = null;
      info.percentUnread = null;
      return;
    }
    info.percentRead = 100.0 * counts.readLines / totalLinesInFile;
    info.percentTentativelyRead = 100.0 * counts.tentativelyReadLines / totalLinesInFile;
    info.percentUnread = 100.0 * counts.unreadLines / totalLinesInFile;
  }
}
