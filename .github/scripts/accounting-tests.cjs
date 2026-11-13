const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const html = fs.readFileSync("app/src/main/assets/index.html", "utf8");
function extractFunction(name) {
  const start = html.indexOf("function " + name + "(");
  assert.notEqual(start, -1, "Missing production function: " + name);
  const next = html.indexOf("\nfunction ", start + 1);
  return html.slice(start, next === -1 ? html.length : next);
}

const context = {
  data: {
    payments: [
      { id: "p1", studentId: "s1", amount: 1000, month: "2026-01" },
      { id: "p2", studentId: "s1", amount: 500, month: "2026-03" },
      { id: "p3", studentId: "s1", amount: 700, month: "2026-04" },
      { id: "p4", studentId: "s2", amount: 250, month: "2026-03" }
    ]
  },
  currentMonth: "2026-03"
};
vm.createContext(context);
vm.runInContext(
  [
    extractFunction("validMonth"),
    extractFunction("monthsBetween"),
    extractFunction("paidFor"),
    extractFunction("dueFor")
  ].join("\n"),
  context
);

assert.equal(context.validMonth("2026-01"), true);
assert.equal(context.validMonth("2026-13"), false);
assert.equal(context.monthsBetween("2026-01", "2026-01"), 1, "Start month must be included");
assert.equal(context.monthsBetween("2025-12", "2026-02"), 3, "Month count must cross year boundary");
assert.equal(context.monthsBetween("2026-04", "2026-03"), 0, "Earlier end month must not accrue fees");

const student = { id: "s1", fee: 1000, start: "2026-01", opening: 300 };
assert.equal(context.paidFor("s1", "2026-03"), 500, "Monthly payment history must be filtered by month");
assert.equal(context.dueFor(student, "2026-03"), 1800, "Due must include opening balance and fees through selected month");
assert.equal(context.dueFor(student, "2026-04"), 2100, "Payments after the cutoff must be excluded from earlier due");
context.data.payments.push({ id: "p5", studentId: "s1", amount: 10000, month: "2026-05" });
assert.equal(context.dueFor(student, "2026-05"), 0, "Due must never become negative");
assert.equal(context.dueFor({ id: "s2", fee: 800, start: "2026-03", opening: 0 }, "2026-03"), 550, "Each student's payments must remain isolated");

console.log("Tutor Hisab accounting calculation tests passed.");
