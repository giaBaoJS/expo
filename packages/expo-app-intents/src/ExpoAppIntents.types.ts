// A type-only import, so nothing from ExpoUI is pulled in at runtime on the platforms where App
// Intents do not exist.
import type { ModifierConfig } from '@expo/ui/swift-ui/modifiers';

/**
 * A single recorded App Intent invocation.
 *
 * Invocations are persisted natively until removed with `removePendingInvocationAsync`, so
 * delivery is at-least-once and handlers must be idempotent per `id`.
 */
export type AppIntentInvocation = {
  /**
   * Unique identifier of this invocation. Use it to remove the invocation after handling.
   */
  id: string;
  /**
   * The invocation name passed to `await AppIntentDispatcher.shared.dispatch(name:params:)` in Swift.
   */
  name: string;
  /**
   * Parameters passed from the native intent.
   */
  params: Record<string, unknown>;
  /**
   * Unix timestamp in milliseconds at which the intent ran.
   */
  createdAt: number;
};

/**
 * Handles a snapshot of pending invocations and, after the initial call, the new invocation
 * that triggered this handler call.
 */
export type AppIntentsHandler = (
  pendingIntents: AppIntentInvocation[],
  newIntent: AppIntentInvocation | null
) => void | Promise<void>;

/**
 * An entity exposed to App Intents parameter queries.
 */
export type AppIntentEntity = {
  /**
   * Stable unique identifier.
   */
  id: string;
  /**
   * Display name shown by Siri and the Shortcuts app, and matched against speech.
   */
  title: string;
  /**
   * Optional secondary text shown in disambiguation UI.
   */
  subtitle?: string;
  /**
   * Alternative spoken names that resolve to this entity.
   */
  synonyms?: string[];
  /** App-specific string metadata consumed by native AppEntity implementations. */
  metadata?: Record<string, string>;
  /**
   * Whether to keep this entity out of the Spotlight index. It stays resolvable, so Siri can still
   * offer it as a parameter and open it by identifier — it just is not searchable.
   *
   * Only applies to entities registered natively with `registerIndexed`. Defaults to `false`.
   *
   * @platform ios
   */
  hideInSpotlight?: boolean;
};

/**
 * ExpoUI modifier config that associates a SwiftUI view with an AppEntity identifier.
 *
 * Built on `@expo/ui`'s own `ModifierConfig` rather than restating its shape, so that a change to
 * what the `modifiers` prop accepts is a type error here instead of a value ExpoUI rejects at
 * runtime.
 */
export type AppEntityIdentifierModifier = ModifierConfig & {
  $type: 'appEntityIdentifier';
  /** App-specific entity kind registered natively, for example `person` or `dish`. */
  entity: string;
  /** Stable entity id from the matching App Intents entity catalog. */
  id: string;
};

export type ExpoAppIntentsModuleEvents = {
  onIntent: (invocation: AppIntentInvocation) => void;
};
