# AdMob / Firebase Release Checklist

## Firebase

- Add the app to Firebase Console with package `com.taxipro`.
- Download `google-services.json` and place it in `app/google-services.json`.
- Enable Google Analytics for the Firebase project.
- Create these Remote Config parameters if you want to override app defaults:
  - `ads_use_test_ids`
  - `admob_banner_ad_unit_id`
  - `admob_interstitial_ad_unit_id`
  - `admob_rewarded_ad_unit_id`
  - `ad_rewarded_credits`
  - `ad_ride_credit_cost`
  - `ad_calculator_credit_cost`
  - `ad_zone_slot_credit_cost`
  - `free_daily_rides`
  - `free_daily_calculator`
  - `free_zone_limit`
  - `credit_max_extra_zone_slots`
  - `ad_interstitial_ride_interval`
  - `ad_interstitial_cooldown_ms`
  - `banner_ads_enabled`
  - `interstitial_ads_enabled`
  - `rewarded_ads_enabled`
  - `admob_mediation_ready`
  - `native_ads_experiment_enabled`

## AdMob

- Create the real AdMob app and ad units.
- The manifest app id is now configured as `ca-app-pub-6621304807079356~7365210074`.
- Keep `ads_use_test_ids=false` for release.
- Real ad unit IDs are configured as fallbacks in the app; keep the same values in Remote Config.
- Use Settings -> Ads debug -> Ad Inspector on a test device before release.

## app-ads.txt

- Copy `release-notes/app-ads.txt` to your public website as `/app-ads.txt`.
- Developer website: `https://wizzerkrizzer.github.io/`.
- The app-ads.txt URL must open at `https://wizzerkrizzer.github.io/app-ads.txt`.

## Later Optimization

- Enable mediation only after there are enough real impressions to compare eCPM and fill rate.
- Test native ads later only if banner revenue is weak or banner UX is too easy to ignore.
