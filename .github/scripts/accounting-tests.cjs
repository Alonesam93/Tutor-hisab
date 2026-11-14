const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");

const html = fs.readFileSync("app/src/main/assets/index.html", "utf8");
function extractFunction(name) {
  const start = html.indexOf("function " + name + "(");
  assert.notEqual(start, -1, "Missing production function: " + name);
  const nextFunction = html.indexOf("\nfunction ", start + 1);
  const nextAsyncFunction = html.indexOf("\nasync function ", start + 1);
  const next = [nextFunction, nextAsyncFunction].filter(index => index !== -1).sort((a, b) => a - b)[0] ?? -1;
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
    extractFunction("emptyData"),
    extractFunction("normalizeData"),
    extractFunction("validMonth"),
    extractFunction("monthsBetween"),
    extractFunction("paidFor"),
    extractFunction("dueFor"),
    extractFunction("scheduledFeeForMonth"),
    extractFunction("totalPaidAllTime"),
    extractFunction("stableRecord"),
    extractFunction("preferLocalRecord"),
    extractFunction("mergeRecordMaps")
  ].join("\n"),
  context
);

assert.equal(context.validMonth("2026-01"), true);
assert.equal(context.validMonth("2026-13"), false);
const normalizedLegacy = context.normalizeData({
  students: [{ id: "legacy-s1", name: "Rahim", fee: "1200", start: "2026-01" }],
  payments: [{ id: "legacy-p1", studentId: "legacy-s1", amount: "300", month: "2026-02" }],
  deletedStudentIds: ["old-s1", 7, "old-s1"]
});
assert.equal(normalizedLegacy.students[0].opening, 0, "Legacy students missing opening balance must be normalized");
assert.equal(normalizedLegacy.students[0].fee, 1200, "Legacy student fee strings must normalize to numbers");
assert.equal(normalizedLegacy.payments[0].date, "2026-02-01", "Legacy payments missing a date must receive a safe month-start date");
assert.equal(normalizedLegacy.payments[0].amount, 300, "Legacy payment amounts must normalize to numbers");
assert.deepEqual(Array.from(normalizedLegacy.deletedStudentIds), ["old-s1"], "Legacy tombstones must be normalized and deduplicated");
assert.equal(context.monthsBetween("2026-01", "2026-01"), 1, "Start month must be included");
assert.equal(context.monthsBetween("2025-12", "2026-02"), 3, "Month count must cross year boundary");
assert.equal(context.monthsBetween("2026-04", "2026-03"), 0, "Earlier end month must not accrue fees");

const student = { id: "s1", fee: 1000, start: "2026-01", opening: 300 };
assert.equal(context.paidFor("s1", "2026-03"), 500, "Monthly payment history must be filtered by month");
assert.equal(context.dueFor(student, "2026-03"), 1800, "Due must include opening balance and fees through selected month");
assert.equal(context.dueFor(student, "2026-04"), 2100, "Payments after the cutoff must be excluded from earlier due");
assert.equal(context.scheduledFeeForMonth([{ fee: 1000, start: "2026-01" }, { fee: 500, start: "2026-04" }], "2026-03"), 1000, "Students whose fee start month is in the future must not inflate this month's scheduled tuition");
assert.equal(context.totalPaidAllTime([{ amount: 1000, month: "2026-01" }, { amount: 500, month: "2026-03" }]), 1500, "Homepage total deposits must include all payment months");
context.data.payments.push({ id: "p5", studentId: "s1", amount: 10000, month: "2026-05" });
assert.equal(context.dueFor(student, "2026-05"), 0, "Due must never become negative");
assert.equal(context.dueFor({ id: "s2", fee: 800, start: "2026-03", opening: 0 }, "2026-03"), 550, "Each student's payments must remain isolated");
assert.equal(context.preferLocalRecord({ updatedAt: 20 }, { updatedAt: 10 }), true, "Newer local edits must win");
assert.equal(context.preferLocalRecord({ updatedAt: 10 }, { updatedAt: 20 }), false, "Newer cloud edits must not be overwritten by stale local data");
const localRecords = new Map([["s1", { id: "s1", fee: 1200, updatedAt: 20 }], ["s2", { id: "s2", fee: 1500, updatedAt: 10 }], ["s4", { id: "s4", fee: 900, updatedAt: 5 }]]);
const cloudRecords = new Map([["s1", { id: "s1", fee: 1000, updatedAt: 10 }], ["s2", { id: "s2", fee: 1800, updatedAt: 30 }], ["s3", { id: "s3", fee: 700, updatedAt: 5 }]]);
const mergedRecords = context.mergeRecordMaps(localRecords, cloudRecords, new Set(["s3"]));
assert.equal(mergedRecords.get("s1").fee, 1200, "Newer local record must sync");
assert.equal(mergedRecords.get("s2").fee, 1800, "Newer cloud record must be preserved");
assert.equal(mergedRecords.get("s4").fee, 900, "Local-only record must be retained");
assert.equal(mergedRecords.has("s3"), false, "Deleted records must remain excluded");
assert.ok(html.includes("function markChangedRecords()") && html.includes("item.updatedAt=changedAt"), "Edits must carry a local update timestamp for safe multi-device sync");
assert.ok(html.includes("const paymentIds=new Set()") && html.includes("paymentIds.has(p.id)"), "Backup restore must reject duplicate payment IDs");
const trackingContext = {
  data: {
    students: [
      { id: "s1", fee: 1200, updatedAt: 100 },
      { id: "s2", fee: 800 }
    ],
    payments: [{ id: "p1", amount: 150, updatedAt: 100 }]
  },
  STORAGE_KEY: "account-data",
  localStorage: {
    getItem: () => JSON.stringify({
      students: [{ id: "s1", fee: 1000, updatedAt: 100 }],
      payments: [{ id: "p1", amount: 100, updatedAt: 100 }]
    })
  },
  Date: { now: () => 999 }
};
vm.createContext(trackingContext);
vm.runInContext([extractFunction("recordContent"), extractFunction("markChangedRecords")].join("\n"), trackingContext);
trackingContext.markChangedRecords();
assert.equal(trackingContext.data.students[0].updatedAt, 999, "Edited student must receive a new update timestamp");
assert.equal(trackingContext.data.students[1].updatedAt, 999, "New student must receive an update timestamp");
assert.equal(trackingContext.data.payments[0].updatedAt, 999, "Edited payment must receive a new update timestamp");

const firestoreRules = fs.readFileSync("firestore.rules", "utf8");
assert.match(firestoreRules, /match \/users\/\{userId\}/, "User-scoped Firestore rule is required");
assert.match(firestoreRules, /function isOwner\(userId\)[\s\S]*request\.auth\.uid\s*==\s*userId/, "Firestore owner check must compare the signed-in UID");
assert.match(firestoreRules, /match \/students\/\{studentId\}[\s\S]*allow create, update: if isOwner\(userId\)[\s\S]*deletedStudentIds/, "Student writes must be owner-only and reject tombstoned IDs");
assert.match(firestoreRules, /match \/payments\/\{paymentId\}[\s\S]*allow create, update: if isOwner\(userId\)[\s\S]*deletedPaymentIds[\s\S]*deletedStudentIds/, "Payment writes must be owner-only and reject tombstoned IDs");
assert.doesNotMatch(firestoreRules, /allow\s+(read|write|read,\s*write)\s*:\s*if\s+true/, "Public Firestore access is forbidden");
assert.ok(firestoreRules.includes("deletedStudentIds") && firestoreRules.includes("deletedPaymentIds"), "Firestore must block writes to tombstoned records");
assert.ok(firestoreRules.includes("existsAfter(") && firestoreRules.includes("request.resource.data.amount > 0") && firestoreRules.includes("request.resource.data.fee > 0"), "Firestore must validate payment references and positive accounting amounts");
assert.ok(firestoreRules.includes("request.resource.data.id == studentId") && firestoreRules.includes("request.resource.data.id == paymentId"), "Firestore document IDs must match the stored record IDs");
assert.ok(firestoreRules.includes("getAfter(/databases/$(database)/documents/users/$(userId)/students/$(studentId)).data.start") && firestoreRules.includes("request.resource.data.month >= studentStartMonthAfter(userId, request.resource.data.studentId)"), "Firestore must reject payments before the student fee start month");
assert.ok(firestoreRules.includes("(0[1-9]|[12][0-9]|3[01])") , "Firestore must reject impossible payment day numbers");
assert.ok(html.includes("cloud.deletedStudentIds") && html.includes("cloud.deletedPaymentIds"), "Cloud sync must import deletion tombstones from the account document");
assert.ok(html.includes("local.replaceCloud===true") && html.includes("FieldValue.arrayUnion(...deletedStudents)"), "Restore must replace cloud data and normal sync must propagate deletion tombstones");
assert.ok(html.includes("tutorHisabProfilePendingSync_") && html.includes("localStorage.removeItem(pendingProfileKey)"), "Offline profile writes must retry Cloud sync after reconnect");
assert.ok(html.includes("Number(pendingProfile.updatedAt||0)>=Number(cloudProfile.updatedAt||0)") && html.includes("profilePending?{...(cloud.profile||{}),...profile}"), "Offline profile edits must not be overwritten by an older Cloud profile");
assert.ok(html.includes('photoData:signupPhotoData,email,updatedAt:Date.now()') && html.includes('profile={...profileValues(),updatedAt:Date.now()}'), "Profile creation and edits must record update timestamps");
assert.ok(html.includes('id="firstNotificationStep"') && html.includes('id="firstNotificationContinue"') && html.includes('id="firstNotificationSkip"'), "First-run notification explanation and choices are required");
assert.ok(html.includes('id="signupPhotoInput"') && html.includes('photoData:signupPhotoData') && html.includes("function compressProfileImage"), "Sign-up must support an optional compressed profile photo");
assert.ok(html.includes('<details class="signup-extra-details">') && html.includes('id="signupName" type="text" autocomplete="name" required'), "Sign-up must prioritize required identity fields and collapse optional profile details");
assert.ok(html.includes('id="passwordHint"') && html.includes("কমপক্ষে ৬ অক্ষরের"), "Sign-up password requirements must be clearly explained");
assert.ok(html.includes('id="authPrivacyNote"') && html.includes("kept separate for each account"), "Authentication screens must explain per-account data separation");
assert.ok(html.includes('<form id="authForm" autocomplete="on">') && html.includes('type="submit" id="loginSignIn"') && html.includes('event.preventDefault();emailSignIn();'), "Authentication must use a semantic form for autofill and keyboard submission");
assert.ok(html.includes('if(!/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(email))'), "Email validation must reject malformed addresses without rejecting valid whitespace-free addresses");
assert.ok(html.includes("async function resetLoginPassword()") && html.includes("Enter your email first.") && html.includes("A password reset link has been sent to your email."), "Password reset must validate email and provide localized success/error feedback");
assert.ok(html.includes('"নোটিফিকেশন চালু রাখবেন?":"Enable notifications?"') && html.includes('"প্রোফাইল ছবি (ঐচ্ছিক)":"Profile photo (optional)"'), "Onboarding and sign-up controls must be translated to English");

const rootHtml = fs.readFileSync("index.html", "utf8");
assert.equal(rootHtml, html, "Repository preview and Android app must use the same UI source");
const privacyHtml = fs.readFileSync("app/src/main/assets/privacy.html", "utf8");
const termsHtml = fs.readFileSync("app/src/main/assets/terms.html", "utf8");
assert.ok(privacyHtml.includes("developers.google.com/admob/android/next-gen/privacy/play-data-disclosure"), "Privacy policy must disclose Google Mobile Ads data handling");
assert.ok(termsHtml.includes("বিজ্ঞাপন"), "Terms must explain advertising");
for (const document of [privacyHtml, termsHtml, html, rootHtml]) {
  assert.ok(!document.includes("tutorhisab.support@gmail.com"), "Unverified support email must not be published");
  assert.ok(!document.includes("01872466893"), "Unverified support phone must not be published");
}
assert.ok(html.includes('href="contact.html"') && html.includes('href="feedback.html"'), "Contact and feedback links must open the packaged in-app support pages");
assert.ok(fs.existsSync("app/src/main/assets/contact.html") && fs.existsSync("app/src/main/assets/feedback.html"), "Packaged Contact and Feedback pages must exist");

const androidJava = fs.readFileSync("app/src/main/java/com/tutorhisab/app/MainActivity.java", "utf8");
assert.ok(androidJava.includes("BuildConfig.DEBUG"), "Debug and release ad configuration must be separated");
assert.ok(androidJava.includes("ca-app-pub-3940256099942544/9214589741"), "Debug banner must use Google's test ad unit");
assert.ok(androidJava.includes("ca-app-pub-3940256099942544/1033173712"), "Debug interstitial must use Google's test ad unit");
assert.ok(androidJava.includes("onSafeAdOpportunity"), "Interstitials must be requested only through the safe navigation bridge");
assert.ok(androidJava.includes("nextInterstitialEligibleAt"), "Interstitial frequency guard is required");

console.log("Tutor Hisab accounting, security-rule, and ad-safety tests passed.");
