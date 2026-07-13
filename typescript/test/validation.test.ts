import { describe, expect, it } from "vitest";
import { CountersValidationError } from "../src/errors.js";
import {
  assertBucket,
  assertMemberKey,
  assertMetadata,
  assertWindow,
  isValidBucket,
  isValidCounterKey,
  isValidMemberKey,
  isValidMetadata,
  isValidWindow,
  toAmount,
  toValue,
} from "../src/validation.js";
import { loadVectors } from "./helpers.js";

const keyVectors = loadVectors<{ valid: string[]; invalid: string[] }>("counter-keys.json");
const amountVectors = loadVectors<{ valid: string[]; invalid: string[] }>("amounts.json");
const bucketVectors = loadVectors<{ valid: string[]; invalid: string[] }>("buckets.json");
const memberVectors = loadVectors<{
  valid: string[];
  invalid: string[];
  metadata: { valid: string[]; invalid: string[] };
}>("member-keys.json");

describe("counter key validation (conformance)", () => {
  it.each(keyVectors.valid)("accepts %j", (key) => {
    expect(isValidCounterKey(key)).toBe(true);
  });
  it.each(keyVectors.invalid)("rejects %j", (key) => {
    expect(isValidCounterKey(key)).toBe(false);
  });
  it("enforces the 200-char length bound", () => {
    expect(isValidCounterKey("a".repeat(200))).toBe(true);
    expect(isValidCounterKey("a".repeat(201))).toBe(false);
  });
});

describe("bucket validation (conformance)", () => {
  it.each(bucketVectors.valid)("accepts %j", (b) => {
    expect(isValidBucket(b)).toBe(true);
    expect(() => assertBucket(b)).not.toThrow();
  });
  it.each(bucketVectors.invalid)("rejects %j", (b) => {
    expect(isValidBucket(b)).toBe(false);
    expect(() => assertBucket(b)).toThrow(CountersValidationError);
  });
});

describe("amount validation (conformance)", () => {
  it.each(amountVectors.valid)("accepts %j -> bigint", (s) => {
    expect(toAmount(s)).toBe(BigInt(s));
  });
  it.each(amountVectors.invalid)("rejects %j", (s) => {
    expect(() => toAmount(s)).toThrow(CountersValidationError);
  });
  it("accepts bigint and safe-integer number inputs", () => {
    expect(toAmount(5n)).toBe(5n);
    expect(toAmount(42)).toBe(42n);
    expect(toAmount(0)).toBe(0n);
  });
  it("rejects negative and unsafe-integer numbers", () => {
    expect(() => toAmount(-1)).toThrow(CountersValidationError);
    expect(() => toAmount(1.5)).toThrow(CountersValidationError);
    expect(() => toAmount(Number.MAX_SAFE_INTEGER + 1)).toThrow(CountersValidationError);
  });
});

describe("member key validation (conformance/member-keys)", () => {
  it.each(memberVectors.valid)("accepts %j", (key) => {
    expect(isValidMemberKey(key)).toBe(true);
    expect(() => assertMemberKey(key)).not.toThrow();
  });
  it.each(memberVectors.invalid)("rejects %j", (key) => {
    expect(isValidMemberKey(key)).toBe(false);
    expect(() => assertMemberKey(key)).toThrow(CountersValidationError);
  });
  it("enforces the 256-char length bound", () => {
    expect(isValidMemberKey("a".repeat(256))).toBe(true);
    expect(isValidMemberKey("a".repeat(257))).toBe(false);
  });
});

describe("metadata byte-cap validation (conformance/member-keys)", () => {
  it.each(memberVectors.metadata.valid)("accepts a valid metadata payload", (md) => {
    expect(isValidMetadata(md)).toBe(true);
    expect(() => assertMetadata(md)).not.toThrow();
  });
  it.each(memberVectors.metadata.invalid)("rejects an over-cap metadata payload", (md) => {
    expect(isValidMetadata(md)).toBe(false);
    expect(() => assertMetadata(md)).toThrow(CountersValidationError);
  });
  it("counts UTF-8 BYTES, not characters (341×'€'+'a' = 1024 bytes ok; 342×'€' = 1026 bytes rejected)", () => {
    expect(isValidMetadata("€".repeat(341) + "a")).toBe(true); // 341*3 + 1 = 1024 bytes
    expect(isValidMetadata("€".repeat(342))).toBe(false); // 342*3 = 1026 bytes, but only 342 chars
  });
});

describe("window validation (conformance/leaderboard)", () => {
  it.each(["1h", "6h", "12h", "1d", "7d", "30d"])("accepts %j", (w) => {
    expect(isValidWindow(w)).toBe(true);
    expect(() => assertWindow(w)).not.toThrow();
  });
  it.each(["2h", "1m", "1mo", "", "7D", "24h"])("rejects %j", (w) => {
    expect(isValidWindow(w)).toBe(false);
    expect(() => assertWindow(w)).toThrow(CountersValidationError);
  });
});

describe("signed value validation (toValue)", () => {
  it("accepts signed integer strings, bigints, and safe-integer numbers", () => {
    expect(toValue("-5")).toBe(-5n);
    expect(toValue("1502")).toBe(1502n);
    expect(toValue(-3n)).toBe(-3n);
    expect(toValue(42)).toBe(42n);
    // Arbitrary precision: a value past 2^64 round-trips exactly.
    expect(toValue("170141183460469231731687303715884105728")).toBe(
      170141183460469231731687303715884105728n,
    );
  });
  it("rejects decimals, non-numeric strings, and unsafe-integer numbers", () => {
    expect(() => toValue("1.5")).toThrow(CountersValidationError);
    expect(() => toValue("abc")).toThrow(CountersValidationError);
    expect(() => toValue(1.5)).toThrow(CountersValidationError);
    expect(() => toValue(Number.MAX_SAFE_INTEGER + 1)).toThrow(CountersValidationError);
  });
});
