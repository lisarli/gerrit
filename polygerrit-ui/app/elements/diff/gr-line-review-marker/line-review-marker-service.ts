/**
 * @license
 * Copyright 2026 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import {Side} from '../../../api/diff';

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
