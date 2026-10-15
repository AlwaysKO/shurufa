/** Calendar-day boundaries independent of the server/database timezone. */
export function beijingDay(now = Date.now()) {
  const dayMs = 86_400_000;
  const offsetMs = 8 * 3_600_000;
  const start = Math.floor((now + offsetMs) / dayMs) * dayMs - offsetMs;
  return {
    date: new Date(start + offsetMs).toISOString().slice(0, 10),
    start: new Date(start),
    end: new Date(start + dayMs),
  };
}
