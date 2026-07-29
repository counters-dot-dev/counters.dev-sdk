import { describe, expect, it } from "vitest";
import {
  CountersValidationError,
  describeMagnitude,
  formatCompact,
  formatFull,
  formatScientific,
} from "../src/index.js";
import type { FormatOptions } from "../src/index.js";
import { loadFormatVectors } from "./helpers.js";

type FormatMode = "compact" | "scientific" | "full" | "magnitude";

interface FormatVector {
  id: string;
  mode: FormatMode;
  value: string;
  options?: FormatOptions;
  expected: string;
}

const vectors = loadFormatVectors<{ cases: FormatVector[] }>();

function replayVector(vector: FormatVector): string {
  switch (vector.mode) {
    case "compact":
      return formatCompact(vector.value, vector.options);
    case "scientific":
      return formatScientific(vector.value, vector.options);
    case "full":
      return formatFull(vector.value);
    case "magnitude":
      return describeMagnitude(vector.value, vector.options);
  }
}

describe("display formatter golden vectors", () => {
  it.each(vectors.cases)("$id", (vector) => {
    expect(replayVector(vector), vector.id).toBe(vector.expected);
  });

  it("contains at least one case for every mode", () => {
    const modes: readonly FormatMode[] = ["compact", "scientific", "full", "magnitude"];
    for (const mode of modes) {
      expect(
        vectors.cases.some((vector) => vector.mode === mode),
        `missing ${mode} vectors`,
      ).toBe(true);
    }
  });
});

const valueFormatters: readonly {
  name: string;
  format: (value: string) => string;
}[] = [
  { name: "formatCompact", format: (value) => formatCompact(value) },
  { name: "formatScientific", format: (value) => formatScientific(value) },
  { name: "formatFull", format: (value) => formatFull(value) },
  { name: "describeMagnitude", format: (value) => describeMagnitude(value) },
];

describe.each(valueFormatters)("$name validation", ({ format }) => {
  it.each(["", "-", "1.5", "+5", " 5", "abc"])("rejects %j", (value) => {
    expect(() => format(value)).toThrow(CountersValidationError);
  });
});

const optionFormatters: readonly {
  name: string;
  format: (options: FormatOptions) => string;
}[] = [
  { name: "formatCompact", format: (options) => formatCompact("1000", options) },
  { name: "formatScientific", format: (options) => formatScientific("1000", options) },
  { name: "describeMagnitude", format: (options) => describeMagnitude("1000", options) },
];

describe.each(optionFormatters)("$name option validation", ({ format }) => {
  it.each([-1, 21, 1.5, NaN])("rejects maxFractionDigits %s", (maxFractionDigits) => {
    expect(() => format({ maxFractionDigits })).toThrow(CountersValidationError);
  });
});
