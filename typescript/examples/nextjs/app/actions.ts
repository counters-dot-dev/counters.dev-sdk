"use server";

import { counters } from "./counters.js";

export async function likePost(postId: string): Promise<string> {
  const likes = counters.counter(`post:${postId}:likes`);

  // Unlike the view events feeding the sparkline route, which a long-lived collector can buffer with
  // views.add(1), a like changes visible UI state. Fire-and-forget returns no value and may fail later;
  // only awaited addNow() confirms the write and gives this Server Action what React needs to render.
  const updated = await likes.addNow(1n);

  // The SDK returns an exact decimal string, which could be "18446744073709551617" (> unsigned 64-bit).
  // Render it as-is, or use BigInt(updated.value) for arithmetic. Number(updated.value) silently
  // rounds it because the value also exceeds Number.MAX_SAFE_INTEGER.
  return updated.value;
}
