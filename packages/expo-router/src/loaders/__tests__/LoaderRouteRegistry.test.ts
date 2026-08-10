import type { ReactNavigationState } from '../../global-state/types';
import { createLoaderRouteRegistry } from '../LoaderRouteRegistry';

const navigationState = (routes: object[]) => ({ routes }) as unknown as ReactNavigationState;

describe('LoaderRouteRegistry', () => {
  it('walks the full nested state tree rather than only the focused branch', () => {
    const registry = createLoaderRouteRegistry();
    registry.claim('deep-route', '/p');

    const abandonedPaths = registry.reconcile(
      navigationState([
        { key: 'focused-tab' },
        {
          key: 'inactive-tab',
          state: navigationState([
            {
              key: 'nested-layout',
              state: navigationState([{ key: 'deep-route' }]),
            },
          ]),
        },
      ])
    );

    expect(abandonedPaths).toEqual(new Set());
  });

  it('reports an old path when the same key changes paths', () => {
    const registry = createLoaderRouteRegistry();

    expect(registry.claim('post-route', '/posts/1')).toBeUndefined();
    expect(registry.claim('post-route', '/posts/2')).toBe('/posts/1');
  });

  it('does not report a path until its final owner is removed', () => {
    const registry = createLoaderRouteRegistry();
    registry.claim('route-1', '/p');
    registry.claim('route-2', '/p');

    expect(registry.reconcile(navigationState([{ key: 'route-2' }]))).toEqual(new Set());
    expect(registry.reconcile(navigationState([]))).toEqual(new Set(['/p']));
  });

  it('reports every owned path when the navigation state is reset', () => {
    const registry = createLoaderRouteRegistry();
    registry.claim('route-1', '/a');
    registry.claim('route-2', '/b');

    expect(registry.reconcile(undefined)).toEqual(new Set(['/a', '/b']));
  });

  it('can reset route associations without reporting abandoned paths', () => {
    const registry = createLoaderRouteRegistry();
    registry.claim('route-1', '/p');

    registry.reset();

    expect(registry.reconcile(navigationState([]))).toEqual(new Set());
  });
});
