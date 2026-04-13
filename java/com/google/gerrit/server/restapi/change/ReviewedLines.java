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

package com.google.gerrit.server.restapi.change;

import com.google.gerrit.extensions.client.ReviewStatus;
import com.google.gerrit.extensions.common.LineReviewedInfo;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.change.AccountPatchLineReviewStore;
import com.google.gerrit.server.change.FileResource;
import com.google.gerrit.server.plugincontext.PluginItemContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** REST API for line-level and region-level reviewed flags within a file. */
public class ReviewedLines {

  @Singleton
  public static class GetReviewedLines implements RestReadView<FileResource> {
    private final PluginItemContext<AccountPatchLineReviewStore> accountPatchLineReviewStore;

    @Inject
    GetReviewedLines(
        PluginItemContext<AccountPatchLineReviewStore> accountPatchLineReviewStore) {
      this.accountPatchLineReviewStore = accountPatchLineReviewStore;
    }

    @Override
    public Response<List<LineReviewedInfo>> apply(FileResource resource) {
      Optional<AccountPatchLineReviewStore.PatchSetWithReviewedLines> result =
          accountPatchLineReviewStore.call(
              s ->
                  s.findReviewedLines(
                      resource.getPatchKey().patchSetId(),
                      resource.getAccountId(),
                      resource.getPatchKey().fileName()));

      List<LineReviewedInfo> infos = new ArrayList<>();
      result.ifPresent(
          patchSetWithReviewedLines -> {
            for (AccountPatchLineReviewStore.ReviewedLine line :
                patchSetWithReviewedLines.lines()) {
              LineReviewedInfo info = new LineReviewedInfo();
              info.line = line.lineNumber();
              info.side = line.getSide();
              info.status = line.reviewStatus();
              if (line.startLine() != line.endLine()
                  || line.startChar() != 0
                  || line.endChar() != 0) {
                info.range = new com.google.gerrit.extensions.client.Comment.Range();
                info.range.startLine = line.startLine();
                info.range.startCharacter = line.startChar();
                info.range.endLine = line.endLine();
                info.range.endCharacter = line.endChar();
              }
              infos.add(info);
            }
          });
      return Response.ok(infos);
    }
  }

  @Singleton
  public static class PutReviewedLine
      implements RestModifyView<FileResource, com.google.gerrit.extensions.api.changes.LineReviewedInput> {
    private final PluginItemContext<AccountPatchLineReviewStore> accountPatchLineReviewStore;

    @Inject
    PutReviewedLine(
        PluginItemContext<AccountPatchLineReviewStore> accountPatchLineReviewStore) {
      this.accountPatchLineReviewStore = accountPatchLineReviewStore;
    }

    @Override
    public Response<String> apply(
        FileResource resource,
        com.google.gerrit.extensions.api.changes.LineReviewedInput input)
        throws BadRequestException {
      if (input == null || input.line == null || input.line < 1) {
        throw new BadRequestException("line is required (1-based)");
      }
      if (input.status != null && input.status != ReviewStatus.READ) {
        throw new BadRequestException("only READ may be set when marking a line reviewed");
      }
      boolean updated =
          accountPatchLineReviewStore.call(
              s ->
                  s.markLineReviewed(
                      resource.getPatchKey().patchSetId(),
                      resource.getAccountId(),
                      resource.getPatchKey().fileName(),
                      input));
      return updated ? Response.created() : Response.ok();
    }
  }

  @Singleton
  public static class DeleteReviewedLine
      implements RestModifyView<FileResource, com.google.gerrit.extensions.api.changes.LineReviewedInput> {
    private final PluginItemContext<AccountPatchLineReviewStore> accountPatchLineReviewStore;

    @Inject
    DeleteReviewedLine(
        PluginItemContext<AccountPatchLineReviewStore> accountPatchLineReviewStore) {
      this.accountPatchLineReviewStore = accountPatchLineReviewStore;
    }

    @Override
    public Response<?> apply(
        FileResource resource,
        com.google.gerrit.extensions.api.changes.LineReviewedInput input)
        throws BadRequestException {
      if (input == null || input.line == null || input.line < 1) {
        throw new BadRequestException("line is required (1-based)");
      }
      accountPatchLineReviewStore.run(
          s ->
              s.clearLineReviewed(
                  resource.getPatchKey().patchSetId(),
                  resource.getAccountId(),
                  resource.getPatchKey().fileName(),
                  input));
      return Response.none();
    }
  }

  private ReviewedLines() {}
}
