import { describe, expect, it } from "vitest";
import { loadVectors } from "./helpers.js";

const vectors = loadVectors<{
  addition: { a: string; b: string; sum: string }[];
  subtraction: { a: string; b: string; diff: string }[];
}>("bignum.json");

describe("arbitrary-precision addition (conformance)", () => {
  // The same vectors are asserted server-side by the service, proving cross-language agreement —
  // including values that overflow i64/u64, which is the headline feature.
  it.each(vectors.addition)("$a + $b = $sum", ({ a, b, sum }) => {
    expect((BigInt(a) + BigInt(b)).toString()).toBe(sum);
  });

  it("survives a value far beyond u64", () => {
    const huge = "1" + "0".repeat(100);
    expect((BigInt(huge) + 1n).toString()).toBe("1" + "0".repeat(99) + "1");
  });
});

describe("arbitrary-precision subtraction (conformance)", () => {
  it.each(vectors.subtraction)("$a - $b = $diff", ({ a, b, diff }) => {
    expect((BigInt(a) - BigInt(b)).toString()).toBe(diff);
  });
});
