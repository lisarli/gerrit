/**
 * @license
 * Copyright 2026 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import * as sinon from 'sinon';
import '../../../test/common-test-setup';
import {assert} from '@open-wc/testing';
import {Side} from '../../../api/diff';
import {CommentSide} from '../../../api/rest-api';
import {RestLineReviewMarkerService} from './line-review-marker-service';
import {grRestApiMock} from '../../../test/mocks/gr-rest-api_mock';
import {LineReviewedInfo} from '../../../services/gr-rest-api/gr-rest-api';

suite('RestLineReviewMarkerService', () => {
  let service: RestLineReviewMarkerService;
  const changeNum = 42 as any;
  const patchNum = 1 as any;
  const path = 'src/foo.ts';

  setup(() => {
    service = new RestLineReviewMarkerService(
      grRestApiMock,
      changeNum,
      patchNum
    );
  });

  teardown(() => {
    sinon.restore();
  });

  suite('saveLineRangeMarked', () => {
    test('calls saveReviewedLine when marked=true', async () => {
      const stub = sinon
        .stub(grRestApiMock, 'saveReviewedLine')
        .resolves(new Response());

      await service.saveLineRangeMarked({
        path,
        side: Side.RIGHT,
        startLine: 5,
        endLine: 5,
        marked: true,
      });

      assert.isTrue(stub.calledOnce);
      assert.equal(stub.firstCall.args[0], changeNum);
      assert.equal(stub.firstCall.args[1], patchNum);
      assert.equal(stub.firstCall.args[2], path);
      assert.deepEqual(stub.firstCall.args[3], {
        line: 5,
        side: CommentSide.REVISION,
      });
    });

    test('calls deleteReviewedLine when marked=false', async () => {
      const stub = sinon
        .stub(grRestApiMock, 'deleteReviewedLine')
        .resolves(new Response());

      await service.saveLineRangeMarked({
        path,
        side: Side.RIGHT,
        startLine: 5,
        endLine: 5,
        marked: false,
      });

      assert.isTrue(stub.calledOnce);
      assert.equal(stub.firstCall.args[2], path);
      assert.deepEqual(stub.firstCall.args[3], {
        line: 5,
        side: CommentSide.REVISION,
      });
    });

    test('maps Side.LEFT to CommentSide.PARENT', async () => {
      const stub = sinon
        .stub(grRestApiMock, 'saveReviewedLine')
        .resolves(new Response());

      await service.saveLineRangeMarked({
        path,
        side: Side.LEFT,
        startLine: 3,
        endLine: 3,
        marked: true,
      });

      assert.equal(stub.firstCall.args[3].side, CommentSide.PARENT);
    });

    test('includes range when startLine differs from endLine', async () => {
      const stub = sinon
        .stub(grRestApiMock, 'saveReviewedLine')
        .resolves(new Response());

      await service.saveLineRangeMarked({
        path,
        side: Side.RIGHT,
        startLine: 5,
        endLine: 10,
        marked: true,
      });

      assert.deepEqual(stub.firstCall.args[3], {
        line: 5,
        side: CommentSide.REVISION,
        range: {startLine: 5, startCharacter: 0, endLine: 10, endCharacter: 0},
      });
    });

    test('omits range when startLine equals endLine', async () => {
      const stub = sinon
        .stub(grRestApiMock, 'saveReviewedLine')
        .resolves(new Response());

      await service.saveLineRangeMarked({
        path,
        side: Side.RIGHT,
        startLine: 7,
        endLine: 7,
        marked: true,
      });

      assert.isUndefined(stub.firstCall.args[3].range);
    });
  });

  suite('getLineRangeMarkers', () => {
    test('returns empty array when API returns undefined', async () => {
      sinon.stub(grRestApiMock, 'getReviewedLines').resolves(undefined);

      const result = await service.getLineRangeMarkers(path, Side.RIGHT);
      assert.deepEqual(result, []);
    });

    test('maps LineReviewedInfo to LineRangeMarker', async () => {
      const info: LineReviewedInfo = {
        line: 5,
        side: CommentSide.REVISION,
      };
      sinon.stub(grRestApiMock, 'getReviewedLines').resolves([info]);

      const result = await service.getLineRangeMarkers(path, Side.RIGHT);

      assert.equal(result.length, 1);
      assert.deepEqual(result[0], {
        path,
        side: Side.RIGHT,
        startLine: 5,
        endLine: 5,
        marked: true,
      });
    });

    test('maps range fields when range is present', async () => {
      const info: LineReviewedInfo = {
        line: 5,
        side: CommentSide.REVISION,
        range: {startLine: 5, startCharacter: 0, endLine: 10, endCharacter: 0},
      };
      sinon.stub(grRestApiMock, 'getReviewedLines').resolves([info]);

      const result = await service.getLineRangeMarkers(path, Side.RIGHT);

      assert.equal(result[0].startLine, 5);
      assert.equal(result[0].endLine, 10);
    });

    test('filters out markers from the other side', async () => {
      const infos: LineReviewedInfo[] = [
        {line: 1, side: CommentSide.REVISION},
        {line: 2, side: CommentSide.PARENT},
      ];
      sinon.stub(grRestApiMock, 'getReviewedLines').resolves(infos);

      const revisionResult = await service.getLineRangeMarkers(
        path,
        Side.RIGHT
      );
      assert.equal(revisionResult.length, 1);
      assert.equal(revisionResult[0].startLine, 1);

      const parentResult = await service.getLineRangeMarkers(path, Side.LEFT);
      assert.equal(parentResult.length, 1);
      assert.equal(parentResult[0].startLine, 2);
    });

    test('defaults missing side to REVISION', async () => {
      const info: LineReviewedInfo = {line: 3};
      sinon.stub(grRestApiMock, 'getReviewedLines').resolves([info]);

      const result = await service.getLineRangeMarkers(path, Side.RIGHT);
      assert.equal(result.length, 1);
    });
  });
});
