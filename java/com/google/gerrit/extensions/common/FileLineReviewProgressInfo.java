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

package com.google.gerrit.extensions.common;

import java.util.List;

/**
 * Per-file line review progress: whole-file line count as the denominator, and one entry per
 * reviewer who has stored line-review data on this patch set.
 */
public class FileLineReviewProgressInfo {
  /** Total lines in the file at this revision (denominator for the percentages below). */
  public Integer totalLinesInFile;

  /** Overall percent of file lines marked READ by all reviewers. */
  public Double percentRead;

  /**
   * Overall percent of file lines marked TENTATIVELY_READ by at least one reviewer, excluding
   * lines already marked READ by all reviewers.
   */
  public Double percentTentativelyRead;

  /** Overall percent of file lines not marked READ/TENTATIVELY_READ by at least one reviewer. */
  public Double percentUnread;

  public List<ReviewerLineReviewProgressInfo> reviewers;
}
