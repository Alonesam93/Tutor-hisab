# Tutor Hisab Android QA checklist

## Automated checks
- [ ] Embedded JavaScript parses with Node.js
- [ ] Five-tab navigation and four Homepage panels are present
- [ ] SMS Packages, Pro and Elite are present and marked Coming Soon
- [ ] Backup and restore controls remain in Settings
- [ ] Android debug APK builds and is packaged as a workflow artifact

## Physical-device checks
- [ ] First-run notification permission and language selection
- [ ] Email/password sign-up, automatic sign-in, sign-in and email password reset
- [ ] Sign out and sign in as a second teacher; confirm no records or profile data leak
- [ ] Add, edit and delete students; verify historical due calculations
- [ ] Add, edit and delete payments; verify partial and overpayment validation
- [ ] Monthly report totals match payment history and accrued fees
- [ ] Download backup, restore it, reject malformed and other-account backups
- [ ] All five tabs, Homepage panels, Android Back button and predictive Back gesture
- [ ] Keyboard, small screens, large text, TalkBack labels and touch target sizes
- [ ] Offline/online transitions and Firestore sync conflict/retry behavior

A successful debug build is not proof that the physical-device checks passed. Record device model, Android version, test account type, steps, and result for each manual check.
