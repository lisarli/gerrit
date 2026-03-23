/**
 * @license
 * Copyright 2026 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import * as sinon from 'sinon';
import '../../../test/common-test-setup';
import './gr-line-review-marker';
import {Side} from '../../../api/diff';
import {queryAndAssert, waitUntil} from '../../../test/test-utils';
import {assert, fixture, html} from '@open-wc/testing';
import {GrButton} from '../../shared/gr-button/gr-button';
import {
  GrLineReviewMarker,
  LineMarkerToggledEvent,
} from './gr-line-review-marker';
import {MockLineReviewMarkerService} from './line-review-marker-service';

suite('gr-line-review-marker tests', () => {
  let element: GrLineReviewMarker;
  let service: MockLineReviewMarkerService;
  let clock: sinon.SinonFakeTimers;

  setup(async () => {
    clock = sinon.useFakeTimers();
    service = new MockLineReviewMarkerService(50);
    element = await fixture(
      html`<gr-line-review-marker></gr-line-review-marker>`
    );
    element.markerService = service;
  });

  teardown(() => {
    clock.restore();
  });

  test('renders nothing without a selected line', async () => {
    await element.updateComplete;
    assert.equal(element.shadowRoot?.textContent?.trim(), '');
  });

  test('loads existing markers from mock service', async () => {
    void service.saveLineRangeMarked({
      path: 'foo.cc',
      side: Side.RIGHT,
      startLine: 17,
      endLine: 17,
      marked: true,
    });
    await clock.runAllAsync();

    element.path = 'foo.cc';
    element.selectedLine = {lineNum: 17, side: Side.RIGHT};
    await element.updateComplete;
    await clock.runAllAsync();
    await waitUntil(() =>
      Boolean(element.shadowRoot?.querySelector('.indicator'))
    );

    assert.isOk(queryAndAssert(element, '.indicator'));
  });

  test('fires toggle event for selected line', async () => {
    element.path = 'foo.cc';
    element.selectedLine = {lineNum: 17, side: Side.RIGHT};
    await element.updateComplete;
    await clock.runAllAsync();

    let receivedEvent: LineMarkerToggledEvent | undefined;
    element.addEventListener('line-marker-toggled', e => {
      receivedEvent = e as LineMarkerToggledEvent;
    });

    queryAndAssert<GrButton>(element, 'gr-button').click();
    await clock.runAllAsync();

    assert.isDefined(receivedEvent);
    assert.deepEqual(receivedEvent!.detail, {
      lineNum: 17,
      side: Side.RIGHT,
      path: 'foo.cc',
      marked: true,
    });
  });

  test('maintains local marker state', async () => {
    element.path = 'foo.cc';
    element.selectedLine = {lineNum: 17, side: Side.RIGHT};
    await element.updateComplete;
    await clock.runAllAsync();

    queryAndAssert<GrButton>(element, 'gr-button').click();
    await clock.runAllAsync();
    await element.updateComplete;

    const text = (element.shadowRoot?.textContent ?? '')
      .replace(/\s+/g, ' ')
      .trim();
    assert.include(text, 'Marked line 17');
    assert.isOk(queryAndAssert(element, '.indicator'));
  });

  test('persists through mock service and can unmark', async () => {
    element.path = 'foo.cc';
    element.selectedLine = {lineNum: 17, side: Side.RIGHT};
    await element.updateComplete;
    await clock.runAllAsync();

    queryAndAssert<GrButton>(element, 'gr-button').click();
    await clock.runAllAsync();
    queryAndAssert<GrButton>(element, 'gr-button').click();
    await clock.runAllAsync();

    const markersPromise = service.getLineRangeMarkers('foo.cc', Side.RIGHT);
    await clock.runAllAsync();
    assert.deepEqual(await markersPromise, []);
    const text = (element.shadowRoot?.textContent ?? '')
      .replace(/\s+/g, ' ')
      .trim();
    assert.include(text, 'Selected line 17');
  });
});
