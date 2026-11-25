const ENDPOINT = "https://adasms-standalone.vercel.app/api/sms/send";

export async function sendAdasmsSms({ phone, message, apiKey }) {
  if (typeof apiKey !== "string" || apiKey.length < 16) {
    throw Object.assign(new Error("adasms_not_configured"), { code: "adasms_not_configured" });
  }
  const response = await fetch(ENDPOINT, {
    method: "POST",
    headers: { Authorization: "Bearer " + apiKey, "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ phone, message }),
    signal: AbortSignal.timeout(20000)
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw Object.assign(new Error("adasms_request_failed"), { code: "adasms_provider_http_" + response.status, status: response.status });
  }
  return {
    status: typeof data.status === "string" ? data.status.slice(0, 60) : "accepted",
    messageId: data.messageId || data.id || data.smsId || null
  };
}
