/**
 * @license
 * Copyright 2026 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import '../../shared/gr-button/gr-button';
import {DisplayLine, Side} from '../../../api/diff';
import {sharedStyles} from '../../../styles/shared-styles';
import {fire} from '../../../utils/event-util';
import {css, html, LitElement, nothing} from 'lit';
import {customElement, property, state} from 'lit/decorators.js';
import {
  LineRangeMarker,
  LineReviewMarkerService,
  MockLineReviewMarkerService,
} from './line-review-marker-service';

export interface LineMarkerToggledDetail extends DisplayLine {
  path: string;
  marked: boolean;
}

export type LineMarkerToggledEvent = CustomEvent<LineMarkerToggledDetail>;

declare global {
  interface HTMLElementEventMap {
    'line-marker-toggled': LineMarkerToggledEvent;
  }
}

@customElement('gr-line-review-marker')
export class GrLineReviewMarker extends LitElement {
  @property({type: Object}) selectedLine?: DisplayLine;

  @property({type: String}) path?: string;

  @property({type: Boolean}) disabled = false;

  markerService: LineReviewMarkerService = new MockLineReviewMarkerService();

  @state() private markers: LineRangeMarker[] = [];

  @state() private loading = false;

  private loadRequestId = 0;

  static override get styles() {
    return [
      sharedStyles,
      css`
        :host {
          display: block;
        }
        .container {
          align-items: center;
          background: var(--background-color-primary);
          border: 1px solid var(--border-color);
          border-radius: 12px;
          box-shadow: var(--elevation-level-1);
          display: inline-flex;
          gap: var(--spacing-m);
          margin: var(--spacing-l);
          padding: var(--spacing-m);
        }
        .label {
          color: var(--deemphasized-text-color);
          font-size: var(--font-size-small);
        }
        .indicator {
          background: var(--success-foreground);
          border-radius: 999px;
          display: inline-block;
          height: 10px;
          width: 10px;
        }
        .container.loading {
          opacity: 0.7;
        }
      `,
    ];
  }

  protected override updated(changedProperties: Map<PropertyKey, unknown>) {
    if (
      changedProperties.has('path') ||
      changedProperties.has('selectedLine')
    ) {
      this.loadMarkers();
    }
  }

  override render() {
    if (!this.selectedLine || !this.path) return nothing;
    const marked = this.isCurrentLineMarked();
    return html`
      <div class="container ${this.loading ? 'loading' : ''}">
        ${marked ? html`<span class="indicator" aria-hidden="true"></span>` : nothing}
        <span class="label">
          ${marked ? 'Marked' : 'Selected'} line
          ${this.selectedLine.lineNum}
        </span>
        <gr-button
          link
          ?disabled=${this.disabled || this.loading}
          @click=${this.handleToggle}
          aria-label=${marked ? 'Unmark line review' : 'Mark line review'}
        >
          ${this.loading ? 'Saving...' : marked ? 'Unmark' : 'Mark Read'}
        </gr-button>
      </div>
    `;
  }

  private async loadMarkers() {
    if (!this.selectedLine || !this.path) {
      this.markers = [];
      return;
    }
    const requestId = ++this.loadRequestId;
    this.loading = true;
    const markers = await this.markerService.getLineRangeMarkers(
      this.path,
      this.selectedLine.side
    );
    if (requestId !== this.loadRequestId) return;
    this.markers = markers;
    this.loading = false;
  }

  private async handleToggle() {
    if (!this.selectedLine || !this.path || this.disabled) return;
    const lineNum = this.getSelectedLineNumber();
    if (lineNum === undefined) return;
    const marked = !this.isCurrentLineMarked();
    this.loading = true;
    await this.markerService.saveLineRangeMarked(
      this.toLineRangeMarker(
        this.path,
        this.selectedLine.side,
        lineNum,
        marked
      )
    );
    this.markers = await this.markerService.getLineRangeMarkers(
      this.path,
      this.selectedLine.side
    );
    this.loading = false;
    fire(this, 'line-marker-toggled', {
      lineNum,
      side: this.selectedLine.side,
      path: this.path,
      marked,
    });
  }

  private isCurrentLineMarked() {
    const lineNum = this.getSelectedLineNumber();
    if (!this.selectedLine || !this.path || lineNum === undefined) return false;
    const side = this.selectedLine.side;
    return this.markers.some(
      marker =>
        marker.path === this.path &&
        marker.side === side &&
        marker.startLine <= lineNum &&
        marker.endLine >= lineNum
    );
  }

  private getSelectedLineNumber() {
    if (!this.selectedLine) return undefined;
    const {lineNum} = this.selectedLine;
    return typeof lineNum === 'number' ? lineNum : undefined;
  }

  private toLineRangeMarker(
    path: string,
    side: Side,
    lineNum: number,
    marked: boolean
  ): LineRangeMarker {
    return {
      path,
      side,
      startLine: lineNum,
      endLine: lineNum,
      marked,
    };
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'gr-line-review-marker': GrLineReviewMarker;
  }
}
