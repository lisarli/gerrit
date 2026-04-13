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

package com.google.gerrit.extensions.api.changes;

import com.google.gerrit.extensions.client.Comment.Range;
import com.google.gerrit.extensions.client.ReviewStatus;
import com.google.gerrit.extensions.client.Side;

/**
 * Input for marking a line or region as reviewed.
 *
 * <p>Attributes mirror those used for comments: line (1-based), optional range for region-level,
 * and side (PARENT vs REVISION).
 */
public class LineReviewedInput {
  /** Line number (1-based). Required. */
  public Integer line;

  /** Optional range for region-level review. If null, only the line is marked reviewed. */
  public Range range;

  /** Side of the diff: PARENT (0) or REVISION (1). Default is REVISION. */
  public Side side;

  /**
   * Review status of the line or region.
   */
  public ReviewStatus status;
}
