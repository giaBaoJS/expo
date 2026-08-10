import type { LoaderContextValue } from './LoaderContext';

/**
 * Abandons loader state after its path loses its final route owner.
 *
 * Pending entries are detached and aborted, while settled entries are cleared only when they no
 * longer have live subscribers.
 */
export function abandonLoaderPath({ client, store }: LoaderContextValue, path: string): void {
  const entry = store.get(path);
  if (entry === undefined) {
    return;
  }

  if (entry instanceof Promise) {
    client.abort(path);
  } else if (client.hasSubscribers(path)) {
    return;
  }
  if (store.get(path) === entry) {
    store.clear(path);
  }
}
