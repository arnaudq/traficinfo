# Monitoring

Manages five alarms: the three function failure alarms for non-503 invocation errors, repeated
503 availability errors, and handled application exceptions reported through
`traficinfo.FunctionFailures`, plus OSM `NEEDS_REVIEW` and `NOT_FOUND` alarms.
All use a one-minute pending duration, formatted notifications, and the existing
Notifications topic. Function alarms split notifications per metric stream and
have no periodic reminders. OSM alarms group datasource streams and repeat daily
while firing.

Pass `compartment_id`, `function_application_id`, and `notification_topic_id` from
the environment. This module does not create or inspect email subscriptions;
the topic continues delivering to its existing confirmed subscribers. It does not
manage function deployments or metrics IAM permissions.

See [monitoring specification](../../../../specs/monitoring.md) for conditions,
notification contents, and recovery guidance.
