# Tasks — add-structured-logging

Format-only: no behavioural change, existing tests unaffected. The JSON schema comes from
`logstash-logback-encoder`'s defaults + a `service` custom field.

## 1. Dependency & logback

- [x] 1.1 Add `net.logstash.logback % logstash-logback-encoder % 8.0` to `build.sbt`
- [x] 1.2 `logback.xml`: a `json` appender (`LogstashEncoder`, `customFields {"service":"apollo"}`)
  and a `text` appender, selected by `LOG_FORMAT` — `${LOG_FORMAT:-text}` picks the appender

## 2. Container default

- [x] 2.1 Set `LOG_FORMAT=json` as a Docker image env default (shipped image logs JSON; local
  `sbt run` with no override stays text)

## 3. Verify

- [x] 3.1 **Verify**: run the container — an ERROR-with-exception line is single-line JSON with
  `level`, `service=apollo`, `logger_name`, `message`, `stack_trace`; local run stays text
- [x] 3.2 Full suite + `scalafmtCheckAll` (unaffected — no test asserts log format)
