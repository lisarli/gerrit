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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.extensions.client.ReviewStatus;
import org.junit.Test;

public class FileLineReviewProgressComputerTest {
  private static final String FILE = "a.txt";

  @Test
  public void compute_readOverridesTentativeAndParentSideIgnored() {
    FileLineReviewProgressComputer.Counts counts =
        FileLineReviewProgressComputer.compute(
            ImmutableList.of(
                line(1, 1, 1, ReviewStatus.TENTATIVELY_READ),
                line(1, 1, 2, ReviewStatus.READ),
                line(3, 3, 0, ReviewStatus.READ)),
            5);

    assertThat(counts.readLines).isEqualTo(1);
    assertThat(counts.tentativelyReadLines).isEqualTo(0);
    assertThat(counts.unreadLines).isEqualTo(4);
  }

  @Test
  public void compute_mergesOverlappingRangesWithoutDoubleCounting() {
    FileLineReviewProgressComputer.Counts counts =
        FileLineReviewProgressComputer.compute(
            ImmutableList.of(
                line(2, 4, 1, ReviewStatus.READ),
                line(3, 5, 1, ReviewStatus.READ),
                line(6, 7, 1, ReviewStatus.TENTATIVELY_READ)),
            10);

    assertThat(counts.readLines).isEqualTo(4);
    assertThat(counts.tentativelyReadLines).isEqualTo(2);
    assertThat(counts.unreadLines).isEqualTo(4);
  }

  @Test
  public void compute_clipsOutOfBoundsRangesToFileSize() {
    FileLineReviewProgressComputer.Counts counts =
        FileLineReviewProgressComputer.compute(
            ImmutableList.of(
                line(-3, 2, 1, ReviewStatus.READ),
                line(9, 20, 1, ReviewStatus.TENTATIVELY_READ)),
            10);

    assertThat(counts.readLines).isEqualTo(2);
    assertThat(counts.tentativelyReadLines).isEqualTo(2);
    assertThat(counts.unreadLines).isEqualTo(6);
  }

  @Test
  public void compute_zeroOrNegativeFileSizeReturnsAllZeroes() {
    FileLineReviewProgressComputer.Counts zero =
        FileLineReviewProgressComputer.compute(
            ImmutableList.of(line(1, 1, 1, ReviewStatus.READ)), 0);
    FileLineReviewProgressComputer.Counts negative =
        FileLineReviewProgressComputer.compute(
            ImmutableList.of(line(1, 1, 1, ReviewStatus.TENTATIVELY_READ)), -1);

    assertThat(zero.readLines).isEqualTo(0);
    assertThat(zero.tentativelyReadLines).isEqualTo(0);
    assertThat(zero.unreadLines).isEqualTo(0);
    assertThat(negative.readLines).isEqualTo(0);
    assertThat(negative.tentativelyReadLines).isEqualTo(0);
    assertThat(negative.unreadLines).isEqualTo(0);
  }

  private static AccountPatchLineReviewStore.ReviewedLine line(
      int startLine, int endLine, int side, ReviewStatus status) {
    return AccountPatchLineReviewStore.ReviewedLine.create(
        FILE, endLine, (short) side, startLine, 0, endLine, 0, status);
  }
}
