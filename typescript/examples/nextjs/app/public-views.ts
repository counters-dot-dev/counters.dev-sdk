"use client";

import { PublishableCountersClient } from "@counters.dev/sdk";

// NEXT_PUBLIC_* values are embedded in browser JavaScript. Only a scoped, read-only pk_ token is
// safe here: writes and out-of-scope reads receive 403. Never expose COUNTERS_API_KEY or any writable
// credential in a Client Component.
const publishableToken = process.env.NEXT_PUBLIC_COUNTERS_PUBLISHABLE_TOKEN;
if (!publishableToken?.startsWith("pk_")) {
  throw new Error("NEXT_PUBLIC_COUNTERS_PUBLISHABLE_TOKEN must contain a pk_ token");
}

const publicCounters = new PublishableCountersClient({ apiKey: publishableToken });

export async function readPublicViewCount(postId: string): Promise<string> {
  return (await publicCounters.counter(`post:${postId}:views`).value()).value;
}
