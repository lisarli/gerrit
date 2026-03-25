/**
 * @license
 * Copyright 2026 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import {Side} from '../../../api/diff';
import {CommentSide} from '../../../api/rest-api';
import {NumericChangeId, PatchSetNum} from '../../../types/common';
import {
  LineReviewedInfo,
  LineReviewedInput,
  RestApiService,
} from '../../../services/gr-rest-api/gr-rest-api';

export interface LineRangeMarker {
  path: string;
  side: Side;
  startLine: number;
  endLine: number;
  marked: boolean;
}

export interface LineReviewMarkerService {
  saveLineRangeMarked(marker: LineRangeMarker): Promise<LineRangeMarker>;
  getLineRangeMarkers(path: string, side: Side): Promise<LineRangeMarker[]>;
}

const sleep = (delayMs: number) =>
  new Promise(resolve => window.setTimeout(resolve, delayMs));

export class MockLineReviewMarkerService implements LineReviewMarkerService {
  private readonly markers = new Map<string, LineRangeMarker[]>();

  constructor(private readonly delayMs = 120) {}

  async saveLineRangeMarked(marker: LineRangeMarker): Promise<LineRangeMarker> {
    await sleep(this.delayMs);
    const key = this.computeKey(marker.path, marker.side);
    const markers = this.markers.get(key) ?? [];
    const filtered = markers.filter(
      existing =>
        existing.startLine !== marker.startLine || existing.endLine !== marker.endLine
    );
    if (marker.marked) filtered.push({...marker});
    this.markers.set(key, filtered);
    return {...marker};
  }

  async getLineRangeMarkers(
    path: string,
    side: Side
  ): Promise<LineRangeMarker[]> {
    await sleep(this.delayMs);
    return [...(this.markers.get(this.computeKey(path, side)) ?? [])];
  }

  private computeKey(path: string, side: Side) {
    return `${path}:${side}`;
  }
}

// Data mapping helpers

function sideToCommentSide(side: Side): CommentSide {
  return side === Side.LEFT ? CommentSide.PARENT : CommentSide.REVISION;
}

function commentSideToSide(cs: CommentSide | undefined): Side {
  return cs === CommentSide.PARENT ? Side.LEFT : Side.RIGHT;
}

function toLineReviewedInput(marker: LineRangeMarker): LineReviewedInput {
  const input: LineReviewedInput = {
    line: marker.startLine,
    side: sideToCommentSide(marker.side),
  };
  if (marker.endLine !== marker.startLine) {
    input.range = {
      startLine: marker.startLine,
      startCharacter: 0,
      endLine: marker.endLine,
      endCharacter: 0,
    };
  }
  return input;
}

function fromLineReviewedInfo(info: LineReviewedInfo, path: string): LineRangeMarker {
  const startLine = info.range?.startLine ?? info.line;
  const endLine = info.range?.endLine ?? info.line;
  return {
    path,
    side: commentSideToSide(info.side),
    startLine,
    endLine,
    marked: true,
  };
}

// REST implementation 

export class RestLineReviewMarkerService implements LineReviewMarkerService {
  constructor(
    private readonly restApi: RestApiService,
    private readonly changeNum: NumericChangeId,
    private readonly patchNum: PatchSetNum
  ) {}

  async saveLineRangeMarked(marker: LineRangeMarker): Promise<LineRangeMarker> {
    const input = toLineReviewedInput(marker);
    if (marker.marked) {
      await this.restApi.saveReviewedLine(
        this.changeNum,
        this.patchNum,
        marker.path,
        input
      );
    } else {
      await this.restApi.deleteReviewedLine(
        this.changeNum,
        this.patchNum,
        marker.path,
        input
      );
    }
    return {...marker};
  }

  async getLineRangeMarkers(
    path: string,
    side: Side
  ): Promise<LineRangeMarker[]> {
    const infos = await this.restApi.getReviewedLines(
      this.changeNum,
      this.patchNum,
      path
    );
    if (!infos) return [];
    const commentSide = sideToCommentSide(side);
    return infos
      .filter(
        info => (info.side ?? CommentSide.REVISION) === commentSide
      )
      .map(info => fromLineReviewedInfo(info, path));
  }
}
