import { useEffect, useState } from "react";
import { errorMessage } from "../api";

type Result<T> = {
  key: string;
  data: T | null;
  error: string | null;
  updatedAt: Date | null;
};

export function usePolling<T>(
  key: string,
  loader: (signal: AbortSignal) => Promise<T>,
  interval = 2500,
) {
  const [result, setResult] = useState<Result<T>>({
    key: "",
    data: null,
    error: null,
    updatedAt: null,
  });
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const data = await loader(controller.signal);
        if (!controller.signal.aborted)
          setResult({ key, data, error: null, updatedAt: new Date() });
      } catch (error) {
        if (!controller.signal.aborted)
          setResult((previous) => ({
            key,
            data: previous.key === key ? previous.data : null,
            error: errorMessage(error),
            updatedAt: previous.key === key ? previous.updatedAt : null,
          }));
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(poll, interval);
      }
    }
    timer = setTimeout(poll, 0);
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [key, loader, interval, revision]);

  const current =
    result.key === key ? result : { data: null, error: null, updatedAt: null };
  return {
    ...current,
    loading: current.data === null && current.error === null,
    refresh: () => setRevision((value) => value + 1),
  };
}
