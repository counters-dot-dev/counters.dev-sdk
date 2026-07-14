import { CountersClient } from "@counters.dev/sdk";

const apiKey = process.env.COUNTERS_API_KEY;
if (!apiKey || apiKey.startsWith("pk_")) {
  throw new Error("COUNTERS_API_KEY must be a server-only, writable API key");
}

// The error sink matters only for unconfirmed writes such as view telemetry: their caller has no
// promise to catch. Confirmed calls such as addNow() reject their own promise instead.
export const counters = new CountersClient({
  apiKey,
  batch: { onError: (error) => console.error("Unconfirmed counter write failed", error) },
});
