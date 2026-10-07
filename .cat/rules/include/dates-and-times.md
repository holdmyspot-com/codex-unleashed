# Project Dates and Times

## Design Goals

- Give new project-controlled date-time formats unambiguous spellings while preserving existing consumer contracts.
- Preserve a temporal value's meaning, precision, and range when selecting or changing its representation.
- Present instants to users in their timezone, with the zone evident, unless they request another representation.

## Guidance

Before choosing a spelling, identify whether the value is an instant, a calendar date, a local date-time, or a duration,
and who owns its format. Read the consuming schema or tool contract. Preserve existing supported formats and externally
defined representations, including epoch timestamps, timestamp units, Git dates, and archive metadata. This rule does
not authorize changing a protocol, renaming existing identifiers, or converting every numeric timestamp to text.

For a new project-owned machine-readable instant whose consumer does not prescribe another format, use ISO 8601 in
UTC with an explicit `Z`, such as `2026-10-05T15:58:05Z`. Include fractional seconds when needed to preserve the source's
precision; do not round nanosecond metadata to milliseconds or truncate fractions merely to match an example. Preserve
the supported range and exact value through conversion. Document the unit of any numeric timestamp required by a
consumer.

For a new project-owned name containing an instant, use the same UTC representation when its naming contract permits
it. Where punctuation is restricted, use the ISO 8601 basic form, such as `20261005T155805Z`, retaining any required
fractional precision in a spelling permitted by the consumer. Follow a consumer's required naming format instead when
one exists.

Write a project-owned calendar date as `YYYY-MM-DD`; do not add a timezone or invent midnight for a date-only value.
Keep a local date-time local until the consuming operation requires an instant. When that conversion needs a timezone
or resolution of a daylight-saving ambiguity, obtain it from the contract or supplied context rather than inventing an
offset. Durations are elapsed amounts, not calendar timestamps; retain their required units and representation.

For prose presented directly to a user, convert an instant to the user's timezone before writing it, converting the
calendar date too. Make the zone evident with an offset or abbreviation. Use the session's configured user timezone;
do not infer it from a build runner's UTC clock. Honor an explicit timezone or format request for its stated scope.
Machine-readable records remain in UTC by default, including records generated while reporting a local time.

When changing a representation or conversion, verify its consumer contract and round-trip value, precision, and range.
Include the applicable boundary cases: date changes across zones, fractional seconds, offset-free dates, and required
numeric or external formats. A spelling-only rule does not replace the project's maintained behavioral tests.
