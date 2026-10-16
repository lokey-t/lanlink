// A group-wide, last-writer-wins reservation for one metadata-only drag batch.
// Accepted transfers are independent of this reservation.
export function previewOrder(a, b) {
  return !b ? 1 : a.revision - b.revision || (a.origin>b.origin?1:a.origin<b.origin?-1:0) || (a.id>b.id?1:a.id<b.id?-1:0);
}
export function validPreview(value, group) {
  return value && value.group === group && typeof value.origin === 'string' && value.origin.length <= 128
    && /^[a-f0-9-]{36}$/i.test(value.id) && Number.isSafeInteger(value.revision)
    && value.revision > 0 && value.revision <= Date.now() + 86400000;
}
