# Tutor Hisab Android QA checklist

## Automated checks
- [ ] Embedded JavaScript parses with Node.js
- [ ] Five-tab navigation and four Homepage panels are present
- [ ] First-run notification explanation precedes Sign In / Sign Up; English is the default and language changes remain available in Settings; permission is not requested at cold start
- [ ] Sign-up profile photo is resized/compressed and saved with the correct account
- [ ] Offline profile updates are retried after network recovery
- [ ] WebView external navigation cannot access the native JavaScript bridges
- [ ] Root preview and Android asset UI remain identical
- [ ] Upgrade from the previous file:// build preserves local student, payment, profile and onboarding data
- [ ] If the prior Firebase session cannot carry over to the HTTPS origin, the user receives a clear one-time sign-in notice
- [ ] Android versionCode is higher than the previously distributed APK
- [ ] SMS Packages, Pro and Elite are present and marked Coming Soon
- [ ] Backup and restore controls remain in Settings
- [ ] Android debug APK builds and is packaged as a workflow artifact

## Physical-device checks
- [ ] First-run notification permission and language selection
- [ ] Email/password sign-up, automatic sign-in, sign-in and email password reset
- [ ] Optional profile photo, mobile, institution and designation persist to the right teacher account
- [ ] External Privacy, Contact and Feedback links open in the system browser
- [ ] Sign out and sign in as a second teacher; confirm no records or profile data leak
- [ ] Add, edit and delete students; verify historical due calculations
- [ ] Add, edit and delete payments; verify partial and overpayment validation
- [ ] Monthly report totals match payment history and accrued fees
- [ ] Download backup, restore it, reject malformed and other-account backups
- [ ] All five tabs, Homepage panels, Android Back button and predictive Back gesture
- [ ] Keyboard, small screens, large text, TalkBack labels and touch target sizes
- [ ] Offline/online transitions and Firestore sync conflict/retry behavior

A successful debug build is not proof that the physical-device checks passed. Record device model, Android version, test account type, steps, and result for each manual check.
