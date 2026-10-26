/** Today as yyyy-MM-dd in the browser's time zone. toISOString() gives the UTC date, which in
 *  India is still yesterday until 05:30 — and on the 1st, last month. */
export const localToday = (): string => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

/** The server's message for a failed request, or the fallback. */
export const errorText = (e: any, fallback: string): string => {
  const d = e?.response?.data;
  // A validation failure's message is the generic "Input validation failed"; the field's own
  // message says what to fix.
  if (d?.validationErrors && typeof d.validationErrors === 'object') {
    const first = Object.values(d.validationErrors)[0];
    if (typeof first === 'string' && first) return first;
  }
  if (typeof d?.message === 'string' && d.message) return d.message;
  return fallback;
};
