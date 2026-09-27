# App languages

`AppLanguage` owns a reactive preference (`fr`, `en`, or `null` to follow the
system). It is initialized by `MomentApplication`, saved independently of the
wallet, and changed from onboarding or the profile. The first supported device
language is used; unsupported device languages fall back to English.

`Message` is the typed catalogue for all app-owned text, including accessibility,
errors and demo copy. Call `tr(Message.Key, arguments…)` at render time. Do not
cache translated strings without including `AppLanguage.code` in the cache key.
Use complete sentences and indexed `{0}` placeholders; never translate user
captions, nicknames, addresses or protocol identifiers. `AppLanguage.locale`
also controls numbers and dates; UTC publication windows remain unchanged.

To add a language, add its catalogue column, extend `Message.format`, register its
code in `AppLanguage.supported`, and add its native label to `LanguagePicker`.
The unit tests check placeholder parity, safe substitution, date boundaries,
amount precision, error translation and language fallback. `LanguageTest` checks
live onboarding switching and preference persistence on Android.
