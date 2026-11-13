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

const firestoreRules = fs.readFileSync("firestore.rules", "utf8");
assert.match(firestoreRules, /match \/users\/\{userId\}/, "User-scoped Firestore rule is required");
assert.match(firestoreRules, /function isOwner\(userId\)[\s\S]*request\.auth\.uid\s*==\s*userId/, "Firestore owner check must compare the signed-in UID");
assert.match(firestoreRules, /match \/students\/\{studentId\}[\s\S]*allow create, update: if isOwner\(userId\)[\s\S]*deletedStudentIds/, "Student writes must be owner-only and reject tombstoned IDs");
assert.match(firestoreRules, /match \/payments\/\{paymentId\}[\s\S]*allow create, update: if isOwner\(userId\)[\s\S]*deletedPaymentIds[\s\S]*deletedStudentIds/, "Payment writes must be owner-only and reject tombstoned IDs");
assert.doesNotMatch(firestoreRules, /allow\s+(read|write|read,\s*write)\s*:\s*if\s+true/, "Public Firestore access is forbidden");
assert.ok(firestoreRules.includes("deletedStudentIds") && firestoreRules.includes("deletedPaymentIds"), "Firestore must block writes to tombstoned records");
assert.ok(html.includes("cloud.deletedStudentIds") && html.includes("cloud.deletedPaymentIds"), "Cloud sync must import deletion tombstones from the account document");
assert.ok(html.includes("local.replaceCloud===true") && html.includes("FieldValue.arrayUnion(...deletedStudents)"), "Restore must replace cloud data and normal sync must propagate deletion tombstones");

const rootHtml = fs.readFileSync("index.html", "utf8");
const privacyHtml = fs.readFileSync("app/src/main/assets/privacy.html", "utf8");
const termsHtml = fs.readFileSync("app/src/main/assets/terms.html", "utf8");
assert.ok(privacyHtml.includes("developers.google.com/admob/android/next-gen/privacy/play-data-disclosure"), "Privacy policy must disclose Google Mobile Ads data handling");
assert.ok(termsHtml.includes("বিজ্ঞাপন"), "Terms must explain advertising");
for (const document of [privacyHtml, termsHtml, html, rootHtml]) {
  assert.ok(!document.includes("tutorhisab.support@gmail.com"), "Unverified support email must not be published");
  assert.ok(!document.includes("01872466893"), "Unverified support phone must not be published");
}
assert.ok(html.includes("github.com/Alonesam93/Tutor-hisab/issues"), "Contact and feedback links must point to the verified project issue tracker");

const androidJava = fs.readFileSync("app/src/main/java/com/tutorhisab/app/MainActivity.java", "utf8");
assert.ok(androidJava.includes("BuildConfig.DEBUG"), "Debug and release ad configuration must be separated");
assert.ok(androidJava.includes("ca-app-pub-3940256099942544/9214589741"), "Debug banner must use Google's test ad unit");
assert.ok(androidJava.includes("ca-app-pub-3940256099942544/1033173712"), "Debug interstitial must use Google's test ad unit");
assert.ok(androidJava.includes("onSafeAdOpportunity"), "Interstitials must be requested only through the safe navigation bridge");
assert.ok(androidJava.includes("nextInterstitialEligibleAt"), "Interstitial frequency guard is required");

console.log("Tutor Hisab accounting, security-rule, and ad-safety tests passed.");
