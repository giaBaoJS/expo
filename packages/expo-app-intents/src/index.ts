import { type EventSubscription, UnavailabilityError } from 'expo-modules-core';
import { useEffect, useRef } from 'react';

import type {
  AppEntityIdentifierModifier,
  AppIntentEntity,
  AppIntentInvocation,
  AppIntentsHandler,
} from './ExpoAppIntents.types';
import ExpoAppIntents from './ExpoAppIntentsModule';

export type * from './ExpoAppIntents.types';

const MAX_SEEN_INVOCATION_IDS = 100;

/**
 * Returns whether App Intents are available on this device.
 * Returns `false` on Android, and web.
 * @platform ios
 */
export function isAvailable(): boolean {
  return ExpoAppIntents != null;
}

/**
 * Adds a listener invoked for live App Intent invocations dispatched while JavaScript is
 * observing.
 *
 * > Use [`getPendingInvocationsAsync()`](#appintentsgetpendinginvocationsasync) or
 * > [`useAppIntents()`](#appintentsuseappintentshandler) to read invocations recorded while
 * > JavaScript was not running.
 * @platform ios
 */
export function addAppIntentListener(
  listener: (invocation: AppIntentInvocation) => void
): EventSubscription {
  if (!ExpoAppIntents) {
    return { remove() {} };
  }
  return ExpoAppIntents.addListener('onIntent', listener);
}

function callAppIntentsHandler(
  handler: AppIntentsHandler,
  pendingIntents: AppIntentInvocation[],
  newIntent: AppIntentInvocation | null
) {
  return Promise.resolve()
    .then(() => handler(pendingIntents, newIntent))
    .catch((error: unknown) => {
      console.warn('Unhandled error in useAppIntents handler.', error);
    });
}

/**
 * Calls `handler` once with the pending invocations recorded while JavaScript was cold, then
 * again for every new invocation received while the component is mounted.
 *
 * `newIntent` is `null` for the initial pending snapshot. Later calls include the current
 * pending snapshot and the new invocation that triggered the call. The initial call is always
 * delivered first, and new invocations are delivered one at a time in arrival order.
 * Pending invocations are not removed automatically; call
 * [`removePendingInvocationAsync(id)`](#appintentsremovependinginvocationasyncid)
 * after handling each one. The queue holds at most 100 invocations, and once it is full the oldest
 * are dropped to make room, so a handler that never removes them does eventually lose invocations.
 *
 * The handler is called with an empty snapshot, and never again, when App Intents are unavailable.
 * @platform ios
 */
export function useAppIntents(handler: AppIntentsHandler): void {
  const handlerRef = useRef(handler);

  useEffect(() => {
    handlerRef.current = handler;
  }, [handler]);

  useEffect(() => {
    let isMounted = true;
    const seenLiveInvocationIds = new Set<string>();
    let deliveryQueue: Promise<void> = Promise.resolve();
    const enqueue = (deliver: () => Promise<void>) => {
      deliveryQueue = deliveryQueue.then(deliver);
    };

    const notify = (
      pendingIntents: AppIntentInvocation[],
      newIntent: AppIntentInvocation | null
    ) => {
      if (!isMounted) {
        return;
      }
      return callAppIntentsHandler(handlerRef.current, pendingIntents, newIntent);
    };

    const deliverNewIntent = async (newIntent: AppIntentInvocation) => {
      try {
        const pendingIntents = await getPendingInvocationsAsync();
        await notify(pendingIntents.length > 0 ? pendingIntents : [newIntent], newIntent);
      } catch (error) {
        if (isMounted) {
          console.error('Could not read pending App Intents invocations.', error);
          await notify([newIntent], newIntent);
        }
      }
    };

    const deliverInitialPendingIntents = async () => {
      try {
        const pendingIntents = await getPendingInvocationsAsync();
        const initialPendingIntents = pendingIntents.filter(
          (invocation) => !seenLiveInvocationIds.has(invocation.id)
        );
        initialPendingIntents.forEach(({ id }) => seenLiveInvocationIds.add(id));
        await notify(initialPendingIntents, null);
      } catch (error) {
        if (isMounted) {
          console.error('Could not read pending App Intents invocations.', error);
          await notify([], null);
        }
      }
    };

    // Attach the live listener first so an invocation cannot arrive between reading pending
    // invocations and subscribing to future ones.
    const subscription = addAppIntentListener((newIntent) => {
      if (seenLiveInvocationIds.has(newIntent.id)) {
        return;
      }
      seenLiveInvocationIds.add(newIntent.id);
      if (seenLiveInvocationIds.size > MAX_SEEN_INVOCATION_IDS) {
        seenLiveInvocationIds.delete(seenLiveInvocationIds.values().next().value!);
      }
      enqueue(() => deliverNewIntent(newIntent));
    });

    enqueue(() => deliverInitialPendingIntents());

    return () => {
      isMounted = false;
      subscription.remove();
    };
  }, []);
}

/**
 * Returns invocations that have not been removed from the pending queue yet, oldest first.
 * Resolves with an empty array when App Intents are unavailable.
 *
 * At most 100 invocations are kept. An app that never removes them keeps only the newest 100.
 *
 * Rejects when the stored queue cannot be read, which means the invocations waiting in it are not
 * delivered. The queue starts empty afterwards, so a later call succeeds.
 * @platform ios
 */
export async function getPendingInvocationsAsync(): Promise<AppIntentInvocation[]> {
  if (!ExpoAppIntents) {
    return [];
  }
  return ExpoAppIntents.getPendingInvocationsAsync();
}

/**
 * Removes a handled invocation so it is no longer delivered or returned as pending.
 * Does nothing when App Intents are unavailable.
 *
 * Rejects when the stored queue cannot be read or written, so a failure to forget a handled
 * invocation is not mistaken for success. A rejection caused by an unreadable queue leaves nothing
 * pending at all: the unreadable data is set aside, so every invocation that was waiting in it is
 * gone, not only this one.
 * @platform ios
 */
export async function removePendingInvocationAsync(id: string): Promise<void> {
  if (!ExpoAppIntents) {
    return;
  }
  return ExpoAppIntents.removePendingInvocationAsync(id);
}

/**
 * Removes all pending invocations.
 * Does nothing when App Intents are unavailable.
 * @platform ios
 */
export async function clearPendingInvocationsAsync(): Promise<void> {
  if (!ExpoAppIntents) {
    return;
  }
  return ExpoAppIntents.clearPendingInvocationsAsync();
}

/**
 * Replaces the entity catalog of the given kind and asks the system to re-train
 * parameterized shortcut phrases against the new values. Entities registered natively with
 * `registerIndexed` also have their Spotlight index rebuilt from the new catalog.
 *
 * Publishing a catalog that matches the stored one does nothing, so it is safe to call this on
 * every app start.
 *
 * The native store is UserDefaults-backed, so it's recommended to keep catalogs compact. For large
 * datasets such as thousands of contacts, songs, or other items, store the full
 * data in your app and publish only the subset that Siri and Shortcuts need.
 *
 * When `kind` or a provided entity is invalid, the whole catalog is rejected, and the previous
 * one kept. The `kind` is invalid when it is empty or whitespace-only. An entity is invalid when
 * its `id` or `title` is empty or whitespace-only, or when another entity in the catalog has the
 * same `id`.
 * @platform ios
 */
export async function setEntityCatalogAsync(
  kind: string,
  entities: AppIntentEntity[]
): Promise<void> {
  if (!ExpoAppIntents) {
    return;
  }
  return ExpoAppIntents.setEntityCatalogAsync(kind, entities);
}

/**
 * Rebuilds the Spotlight index from the stored entity catalog, whether or not the catalog
 * changed. `setEntityCatalogAsync` already keeps the index in step, so this is only needed to
 * recover from an index that no longer matches the catalog: one the system evicted, or one left
 * stale by an app update that changed how entities describe themselves.
 *
 * Pass a `kind` to rebuild one catalog, or omit it to rebuild every kind registered natively with
 * `registerIndexed`. Kinds with no indexed registration are ignored.
 *
 * Rejects when a catalog cannot be read or the index cannot be written, because this is the retry
 * path and a caller that asked for a rebuild has no other way to learn it did not happen. Every
 * kind is attempted before the first failure is reported, so one unreadable catalog does not skip
 * the rest. A kind whose rebuild failed is retried by the next `setEntityCatalogAsync`, even when
 * the catalog it publishes is unchanged.
 *
 * @platform ios
 */
export async function reindexEntitiesAsync(kind?: string): Promise<void> {
  if (!ExpoAppIntents) {
    return;
  }
  return ExpoAppIntents.reindexEntitiesAsync(kind ?? null);
}

/**
 * Returns the current entity catalog of the given kind.
 * Resolves with an empty array when the kind was never published, and when App Intents are
 * unavailable.
 *
 * Rejects when the stored catalog cannot be read.
 * @platform ios
 */
export async function getEntityCatalogAsync(kind: string): Promise<AppIntentEntity[]> {
  if (!ExpoAppIntents) {
    return [];
  }
  return ExpoAppIntents.getEntityCatalogAsync(kind);
}

/**
 * Asks the system to re-evaluate App Shortcut phrases and parameter values.
 *
 * Throws `UnavailabilityError` when App Intents are unavailable, and throws when they are available
 * but the app has no `AppShortcutsProvider`, and so nothing to refresh. Publishing a catalog with
 * [`setEntityCatalogAsync()`](#appintentssetentitycatalogasynckind-entities) also refreshes
 * shortcuts.
 * @platform ios
 */
export async function refreshShortcutsAsync(): Promise<void> {
  if (!ExpoAppIntents) {
    throw new UnavailabilityError('expo-app-intents', 'refreshShortcutsAsync');
  }
  return ExpoAppIntents.refreshShortcutsAsync();
}

/**
 * Returns an ExpoUI SwiftUI modifier config that ties a view to an AppEntity identifier.
 *
 * The `entity` value must be registered from app-target Swift with
 * `AppEntityIdentifierRegistry.shared.register(_:as:)`.
 *
 * @platform ios
 */
export function appEntityIdentifier(entity: string, id: string): AppEntityIdentifierModifier {
  return {
    $type: 'appEntityIdentifier',
    entity,
    id,
  };
}
