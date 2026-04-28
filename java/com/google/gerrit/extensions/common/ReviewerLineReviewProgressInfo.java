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

/**
 * Progress of reviewer on a file, as a percentage of total lines in the file at the
 * revision.
 */
public class ReviewerLineReviewProgressInfo {
  public AccountInfo account;

  /** Percentage of lines marked read ({@code READ}) on the revision side. */
  public Double percentRead;

  /** Percentage of lines tentatively read ({@code TENTATIVELY_READ}), excluding lines also read. */
  public Double percentTentativelyRead;

  /** Percentage of lines with no marker on the revision side. */
  public Double percentUnread;
}
