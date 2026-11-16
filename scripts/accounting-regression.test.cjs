const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const html = fs.readFileSync("app/src/main/assets/index.html", "utf8");
function extract(name, nextName) {
  const start = html.indexOf("function " + name + "(");
  const end = html.indexOf("function " + nextName + "(", start + 1);
  assert.notEqual(start, -1, "Missing app function: " + name);
  assert.notEqual(end, -1, "Missing next app function: " + nextName);
  return html.slice(start, end).trim();
}

const sandbox = {
  data: { students: [], payments: [] },
  currentMonth: "2026-03"
};
vm.createContext(sandbox);
vm.runInContext([
  extract("validMonth", "validDate"),
  extract("monthsBetween", "paidFor"),
  extract("paidFor", "dueFor"),
  extract("dueFor", "paymentOverpaymentMonth"),
  extract("paymentOverpaymentMonth", "addStudent"),
  "this.testFns = { validMonth, monthsBetween, paidFor, dueFor, paymentOverpaymentMonth };"
].join("\n"), sandbox);

const f = sandbox.testFns;
const student = { id: "student-1", start: "2026-01", fee: 100, opening: 50 };
assert.equal(f.validMonth("2026-03"), true);
assert.equal(f.validMonth("2026-13"), false);
assert.equal(f.monthsBetween("2026-01", "2026-01"), 1);
assert.equal(f.monthsBetween("2026-01", "2026-03"), 3);
assert.equal(f.monthsBetween("2026-03", "2026-01"), 0);

sandbox.data = {
  students: [student],
  payments: [
    { id: "p1", studentId: student.id, month: "2026-01", amount: 100 },
    { id: "p2", studentId: student.id, month: "2026-02", amount: 25 }
  ]
};
assert.equal(f.paidFor(student.id), 125);
assert.equal(f.dueFor(student, "2026-03"), 225);

sandbox.data.payments = [
  { id: "late-month", studentId: student.id, month: "2026-03", amount: 350 }
];
assert.equal(
  f.paymentOverpaymentMonth(student, { id: "new-early", studentId: student.id, month: "2026-01", amount: 1 }),
  "2026-03",
  "An earlier payment must not make already-recorded later payments exceed accrued tuition"
);

sandbox.data.payments = [
  { id: "jan", studentId: student.id, month: "2026-01", amount: 100 },
  { id: "feb", studentId: student.id, month: "2026-02", amount: 100 }
];
assert.equal(
  f.paymentOverpaymentMonth(student, { id: "mar", studentId: student.id, month: "2026-03", amount: 100 }),
  null,
  "Valid monthly instalments up to the accrued amount must be accepted"
);
assert.equal(
  f.paymentOverpaymentMonth(student, { id: "feb", studentId: student.id, month: "2026-02", amount: 200 }, "feb"),
  "2026-02",
  "Editing an existing payment must validate the complete cumulative history"
);

console.log("Tutor Hisab accounting regression tests passed.");
