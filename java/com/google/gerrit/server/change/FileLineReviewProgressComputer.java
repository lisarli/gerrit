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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.common.collect.TreeRangeSet;
import com.google.gerrit.extensions.client.ReviewStatus;
import com.google.gerrit.extensions.client.Side;

/** Computes read / tentative / unread line counts for one file from stored markers. */
public final class FileLineReviewProgressComputer {

  public static final class Counts {
    public final int readLines;
    public final int tentativelyReadLines;
    public final int unreadLines;

    Counts(int readLines, int tentativelyReadLines, int unreadLines) {
      this.readLines = readLines;
      this.tentativelyReadLines = tentativelyReadLines;
      this.unreadLines = unreadLines;
    }
  }

  /**
   * Counts lines on the {@link Side#REVISION} side only. Tentative coverage excludes lines that are
   * also covered by an explicit {@link ReviewStatus#READ} marker.
   */
  public static Counts compute(
      ImmutableList<AccountPatchLineReviewStore.ReviewedLine> lines, int totalLinesInFile) {
    if (totalLinesInFile <= 0) {
      return new Counts(0, 0, 0);
    }
    TreeRangeSet<Integer> readRanges = TreeRangeSet.create();
    TreeRangeSet<Integer> tentRanges = TreeRangeSet.create();
    for (AccountPatchLineReviewStore.ReviewedLine line : lines) {
      if (line.getSide() != Side.REVISION) {
        continue;
      }
      int lo = Math.min(line.startLine(), line.endLine());
      int hi = Math.max(line.startLine(), line.endLine());
      if (hi < 1) {
        continue;
      }
      lo = Math.max(lo, 1);
      hi = Math.min(hi, totalLinesInFile);
      if (lo > hi) {
        continue;
      }
      Range<Integer> span = Range.closed(lo, hi);
      if (line.reviewStatus() == ReviewStatus.READ) {
        readRanges.add(span);
      } else if (line.reviewStatus() == ReviewStatus.TENTATIVELY_READ) {
        tentRanges.add(span);
      }
    }
    TreeRangeSet<Integer> tentOnly = TreeRangeSet.create(tentRanges);
    tentOnly.removeAll(readRanges);

    int readCount = countLines(readRanges, totalLinesInFile);
    int tentCount = countLines(tentOnly, totalLinesInFile);
    int unread = totalLinesInFile - readCount - tentCount;
    return new Counts(readCount, tentCount, unread);
  }

  private static int countLines(TreeRangeSet<Integer> rs, int totalLinesInFile) {
    Range<Integer> fileSpan = Range.closed(1, totalLinesInFile);
    int n = 0;
    for (Range<Integer> r : rs.subRangeSet(fileSpan).asRanges()) {
      Range<Integer> clipped = r.intersection(fileSpan);
      if (!clipped.isEmpty()) {
        n += clipped.upperEndpoint() - clipped.lowerEndpoint() + 1;
      }
    }
    return n;
  }

  private FileLineReviewProgressComputer() {}
}
