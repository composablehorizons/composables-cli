import test from "node:test";
import assert from "node:assert/strict";

import { expandPurgeTargets, normalizeTarget } from "../src/cloudflare.js";

test("normalizeTarget keeps full URLs", () => {
  assert.equal(
    normalizeTarget("https://composables.com/ui", "ignored.com"),
    "https://composables.com/ui",
  );
});

test("normalizeTarget expands absolute paths with the configured domain", () => {
  assert.equal(
    normalizeTarget("/ui", "composables.com"),
    "https://composables.com/ui",
  );
});

test("expandPurgeTargets expands multiple absolute paths", () => {
  assert.deepEqual(
    expandPurgeTargets(["/ui", "/compose-unstyled"], "composables.com"),
    [
      "https://composables.com/ui",
      "https://composables.com/compose-unstyled",
    ],
  );
});

test("normalizeTarget rejects relative paths", () => {
  assert.throws(
    () => normalizeTarget("ui", "composables.com"),
    /Expected a full URL or absolute path/,
  );
});
