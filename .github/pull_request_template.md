<!-- This template helps reviewers assess Tallyhook changes consistently. -->
## Summary

<!-- Describe what changed and why. -->

## Verification

- [ ] `./gradlew build`
- [ ] `./gradlew ktlintCheck detekt spotlessCheck`
- [ ] `docker compose config --quiet` (if infrastructure configuration changed)

## Notes for reviewers

<!-- Call out migration, compatibility, or operational considerations. -->
