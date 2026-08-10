import type { ReactNavigationState } from '../global-state/types';

/**
 * Tracks route ownership of loader paths so navigation can abandon entries that are no longer used.
 *
 * Ownership is claimed during render because committed navigation state does not contain resolved
 * loader paths.
 */
export interface LoaderRouteRegistry {
  claim(routeKey: string, path: string): string | undefined;
  reconcile(state: ReactNavigationState | undefined): ReadonlySet<string>;
  reset(): void;
}

export function createLoaderRouteRegistry(): LoaderRouteRegistry {
  // Maps each route instance key to the loader path it currently owns.
  const routePaths = new Map<string, string>();

  function isOwned(path: string): boolean {
    for (const ownedPath of routePaths.values()) {
      if (ownedPath === path) {
        return true;
      }
    }
    return false;
  }

  return {
    claim(routeKey, path) {
      const previousPath = routePaths.get(routeKey);
      if (previousPath === path) {
        return undefined;
      }

      routePaths.set(routeKey, path);
      if (previousPath !== undefined && !isOwned(previousPath)) {
        return previousPath;
      }
      return undefined;
    },

    reconcile(state) {
      const presentKeys = new Set<string>();
      const walk = (node: ReactNavigationState | undefined) => {
        for (const route of node?.routes ?? []) {
          if (route.key) {
            presentKeys.add(route.key);
          }
          walk(route.state);
        }
      };
      walk(state);

      const candidatePaths = new Set<string>();
      for (const [routeKey, path] of routePaths) {
        if (!presentKeys.has(routeKey)) {
          routePaths.delete(routeKey);
          candidatePaths.add(path);
        }
      }
      const ownedPaths = new Set(routePaths.values());
      const abandonedPaths = new Set<string>();
      for (const path of candidatePaths) {
        if (!ownedPaths.has(path)) {
          abandonedPaths.add(path);
        }
      }
      return abandonedPaths;
    },

    reset() {
      routePaths.clear();
    },
  };
}
