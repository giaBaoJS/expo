import { expect, test } from '@jest/globals';

import { ExpoTabRouter } from '../TabRouter';
import type { TriggerMap } from '../common';

test('reports the key selected when preserving nested tab state', () => {
  const triggerMap: TriggerMap = {};
  const router = ExpoTabRouter({ triggerMap });
  const options = { routeNames: ['first', 'second'], routeGetIdList: {} };
  const state = router.getRehydratedState(
    {
      index: 0,
      routes: [
        { key: 'first-key', name: 'first' },
        {
          key: 'second-key',
          name: 'second',
          state: { routes: [{ name: 'child' }] },
        },
      ],
    },
    options
  );

  const result = router.getStateForAction(
    state,
    { type: 'JUMP_TO', payload: { name: 'second' } },
    options
  );

  expect(result?.affectedRouteKey).toBe('second-key');
  expect(result?.state.index).toBe(1);
});
