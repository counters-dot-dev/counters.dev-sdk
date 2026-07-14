import {
  CountersApiError,
  CountersTransportError,
  CountersValidationError,
} from "@counters.dev/sdk";
import { counters } from "../../../../counters.js";

// This is the only piece of Next.js typing the standalone sketch needs. App Router supplies an
// equivalent context at runtime; keeping it local avoids adding Next.js solely for a type import.
type RouteContext = { params: Promise<{ id: string }> };

export async function GET(_request: Request, { params }: RouteContext): Promise<Response> {
  const { id } = await params;
  const to = new Date();
  const from = new Date(to.getTime() - 24 * 60 * 60 * 1_000);

  try {
    const views = counters.counter(`post:${id}:views`);
    // A lifecycle-managed view collector can use views.add(1): no screen needs its post-write total,
    // so batching is ideal. This analytics GET only reads, so refreshing it cannot add views.
    const series = await views.series({ from, to, bucket: "1h", gapfill: true });

    return Response.json({
      points: series.points.map((point) => ({
        at: point.timestamp.toISOString(),
        exactViews: point.value,
      })),
    });
  } catch (error) {
    if (error instanceof CountersValidationError) {
      return Response.json({ error: error.message }, { status: 400 });
    }
    if (error instanceof CountersApiError) {
      const status = error.status >= 400 ? error.status : 502;
      return Response.json(
        { error: error.problem?.title ?? error.message },
        { status },
      );
    }
    if (error instanceof CountersTransportError) {
      return Response.json({ error: "Counters service unavailable" }, { status: 503 });
    }
    throw error;
  }
}
