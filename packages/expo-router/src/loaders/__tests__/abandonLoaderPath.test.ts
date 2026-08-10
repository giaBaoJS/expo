import { LoaderClient } from '../LoaderClient';
import { createLoaderContextValue } from '../LoaderContext';
import { abandonLoaderPath } from '../abandonLoaderPath';
import { readLoaderData } from '../readLoaderData';

const getSignal = (requestInit: RequestInit) => requestInit.signal as AbortSignal;

describe(abandonLoaderPath, () => {
  it('does nothing when the path has no store entry', () => {
    const ctx = createLoaderContextValue(new LoaderClient());
    const abort = jest.spyOn(ctx.client, 'abort');

    abandonLoaderPath(ctx, '/missing');

    expect(abort).not.toHaveBeenCalled();
  });

  it('aborts and identity-clears a pending entry', async () => {
    const ctx = createLoaderContextValue(new LoaderClient());
    let signal!: AbortSignal;
    const pending = readLoaderData(ctx, '/p', (_path, requestInit) => {
      const executionSignal = getSignal(requestInit);
      signal = executionSignal;
      return new Promise((_, reject) => {
        executionSignal.addEventListener('abort', () => reject(executionSignal.reason));
      });
    }) as Promise<unknown>;

    abandonLoaderPath(ctx, '/p');

    expect(signal.aborted).toBe(true);
    expect(ctx.store.get('/p')).toBeUndefined();
    await expect(pending).rejects.toThrow('Failed to load loader data for route: /p');
    expect(ctx.store.get('/p')).toBeUndefined();
  });

  it('cannot resurrect an entry when a custom fetcher ignores abort', async () => {
    const ctx = createLoaderContextValue(new LoaderClient());
    let signal!: AbortSignal;
    let resolveFetch!: (value: string) => void;
    const pending = readLoaderData(ctx, '/p', (_path, requestInit) => {
      signal = getSignal(requestInit);
      return new Promise<string>((resolve) => {
        resolveFetch = resolve;
      });
    }) as Promise<string>;

    abandonLoaderPath(ctx, '/p');
    resolveFetch('ignored-abort');

    expect(signal.aborted).toBe(true);
    await expect(pending).resolves.toBe('ignored-abort');
    expect(ctx.store.get('/p')).toBeUndefined();
  });

  it('preserves a settled entry with a live subscriber', () => {
    const ctx = createLoaderContextValue(new LoaderClient());
    const entry = { data: 'live' };
    ctx.store.set('/p', entry);
    ctx.client.subscribeLoader('/p');

    abandonLoaderPath(ctx, '/p');

    expect(ctx.store.get('/p')).toBe(entry);
  });

  it('clears a parked settled entry', () => {
    const ctx = createLoaderContextValue(new LoaderClient());
    ctx.store.set('/p', { data: 'parked' });

    abandonLoaderPath(ctx, '/p');

    expect(ctx.store.get('/p')).toBeUndefined();
  });

  it('preserves a replacement written synchronously while aborting', () => {
    const ctx = createLoaderContextValue(new LoaderClient());
    const pending = new Promise(() => {});
    const replacement = { data: 'replacement' };
    ctx.client.subscribeLoader('/p');
    ctx.client.execute('/p', (_path, requestInit) => {
      getSignal(requestInit).addEventListener('abort', () => ctx.store.set('/p', replacement));
      return new Promise(() => {});
    });
    ctx.store.set('/p', pending);

    abandonLoaderPath(ctx, '/p');

    expect(ctx.store.get('/p')).toBe(replacement);
  });

  it('preserves a settled replacement written during the liveness check', () => {
    const ctx = createLoaderContextValue(new LoaderClient());
    const replacement = { data: 'replacement' };
    ctx.store.set('/p', { data: 'old' });
    jest.spyOn(ctx.client, 'hasSubscribers').mockImplementation(() => {
      ctx.store.set('/p', replacement);
      return false;
    });

    abandonLoaderPath(ctx, '/p');

    expect(ctx.store.get('/p')).toBe(replacement);
  });
});
