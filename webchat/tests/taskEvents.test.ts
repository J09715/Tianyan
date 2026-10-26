import assert from "node:assert/strict";
import test from "node:test";
import { TaskReceiptTracker, taskEventEffect } from "../src/taskEvents.ts";

test("old completion does not clear a queued successor", () => {
  const effect = taskEventEffect({ taskId: "old", kind: "completed" }, "new", true);
  assert.equal(effect.rememberCompletion, true);
  assert.equal(effect.clearActive, false);
  assert.equal(effect.clearApprovals, false);
});
test("another conversation's approval cannot replace the selected conversation's controls", () => {
  const effect = taskEventEffect({ taskId: "same-id", kind: "waiting_approval" }, "same-id", false);
  assert.equal(effect.clearActive, false);
  assert.equal(effect.showApprovals, false);
});
test("matching terminal event clears controls and records early completion", () => {
  for (const kind of ["completed", "error"]) {
    const effect = taskEventEffect({ taskId: "active", kind }, "active", true);
    assert.equal(effect.clearActive, true);
    assert.equal(effect.clearApprovals, true);
    assert.equal(effect.rememberCompletion, true);
  }
});
test("approval for the selected session can be shown while its successor remains queued", () => {
  const effect = taskEventEffect({ taskId: "waiting", kind: "waiting_approval" }, "queued", true);
  assert.equal(effect.showApprovals, true);
  assert.equal(effect.clearActive, false);
  assert.equal(effect.clearApprovals, false);
});
test("unknown or missing task events cannot clear active controls", () => {
  assert.equal(taskEventEffect({ kind: "completed" }, "active", true).clearActive, false);
  assert.equal(taskEventEffect({ taskId: "active", kind: "unrecognized" }, "active", true).clearActive, false);
});

test("waiting approval before HTTP receipt keeps controls idle after acceptance", () => {
  const receipts = new TaskReceiptTracker();
  receipts.begin("request");
  const event = { taskId: "request", kind: "waiting_approval" };
  assert.equal(taskEventEffect(event, "request", true).clearActive, true);
  receipts.observe(event);
  assert.equal(receipts.accept("request"), null);
});

test("completion before receipt wins even when an older approval event follows", () => {
  const receipts = new TaskReceiptTracker();
  for (const kind of ["completed", "error"]) {
    receipts.begin("request");
    receipts.observe({ taskId: "request", kind });
    receipts.observe({ taskId: "request", kind: "waiting_approval" });
    assert.equal(receipts.accept("request"), null);
  }
});

test("receipt tracking isolates concurrent task IDs and ignores unrelated events", () => {
  const receipts = new TaskReceiptTracker();
  receipts.begin("first");
  receipts.begin("second");
  receipts.observe({ taskId: "first", kind: "waiting_approval" });
  receipts.observe({ taskId: "unrelated", kind: "completed" });
  assert.equal(receipts.accept("second"), "second");
  assert.equal(receipts.accept("first"), null);
  assert.equal(receipts.accept("unrelated"), "unrelated");
});

test("failed requests and accepted receipts discard their remembered events", () => {
  const receipts = new TaskReceiptTracker();
  receipts.begin("request");
  receipts.observe({ taskId: "request", kind: "error" });
  receipts.discard("request");
  receipts.begin("request");
  assert.equal(receipts.accept("request"), "request");
  receipts.observe({ taskId: "request", kind: "waiting_approval" });
  receipts.begin("request");
  assert.equal(receipts.accept("request"), "request");
});
