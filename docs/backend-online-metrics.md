# Direct client online and one-year history (2026-10-06)

The primary online count is the gateway's live authenticated WebSocket registry.
`hytale_gateway_online_users` counts distinct account UUIDs; `hytale_gateway_connections`
counts client sockets, including multiple instances of one account. Menus count as
online. This is not a count of humans, players inside worlds, or every old client:
clients which do not use this gateway are outside this measurement.

Graceful close removes the connection immediately; ping/pong removes a silent
peer within roughly 50 seconds (two 25-second heartbeat ticks). Gateway/Redis
unavailability produces HTTP503 and a missing observation, never an invented zero.
Gateway restart closes sockets; its new registry is populated by actual reconnects.
No UUID, token, address or per-player series is exported.

VictoriaMetrics scrapes the gateway directly every15 seconds, independently of auth
and Redis inventory. Its existing persistent volume retains365 days. New series start
at deployment; the previous token-based estimates are not backfilled as live online.
Historical data which already expired cannot be recovered by extending retention.

Admin Metrics has5m through365d ranges. The online chart shows per-bucket average
and peak account counts plus average connections. Summary shows average, peak,
observed account-hours/client-hours and sample coverage for the selected period.
Hours are sum(samples)*15/3600: sampled connection time, including menus, not gameplay
time or distinct accounts over a year. Missed scrapes are not extrapolated. Very short
sessions between scrapes can be missed. Coverage exposes missing/partial history.
Long ranges use6h/12h/1day buckets and at most366 output points for a year.

`GET /admin/api/activity` now returns source, available, onlineUsers, connections,
deadPeerTimeoutSeconds and timestamp (confirmedOnline is a compatibility alias).
It deliberately no longer returns estimatedOnline, cohort estimates or database inventory.
`GET /admin/api/metrics/timeseries?metric=online_users&range=365d` and metric=connections
return points, peaks and summary. Authentication stays on the existing admin routes.

No Redis KEYS/SCAN or per-account reads are used for online collection/history.
The legacy auth exporter uses ZCOUNT for activity-window gauges; those metrics are
explicitly not live clients. Session-key count was removed from the periodic exporter.
The Metrics page no longer polls full database inventory or all-player hardware;
legacy hardware/telemetry reports remain available by an explicit button.
Hardware inventory is also removed from the automatic Prometheus scrape.
Other admin inventory pages retain their existing behavior.

Checks: gateway tests exercise3 sockets/2 accounts, duplicate account disconnect,
last disconnect and unavailable readiness. Existing Redis restart/ICE tests pass.
Auth tests cover bounded year queries, missing samples, coverage/hours, invalid ranges
and upstream503. Generated browser scripts parse; production scrape target and actual
series queries were checked. Two fixed-cardinality gateway series require no database
migration. Current VM volume was14MiB before extending retention (not a size guarantee).

Retention and rollups use the existing VictoriaMetrics engine:
https://docs.victoriametrics.com/victoriametrics/metricsql/
https://docs.victoriametrics.com/victoriametrics/single-server-victoriametrics/
