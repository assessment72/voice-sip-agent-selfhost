export function parseTelegramAdminIds(value) {
  return new Set(String(value || "")
    .split(",")
    .map(item => item.trim())
    .filter(item => /^\d+$/.test(item)));
}
