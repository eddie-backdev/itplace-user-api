# User API test verification

Run the required verification from this directory with:

```bash
./scripts/test-required.sh
```

The runner executes the complete JUnit suite, writes JaCoCo XML and HTML reports, then fails if any JUnit case was skipped or if the suite produced no test cases. This keeps ordinary `./gradlew test` usable when Docker is unavailable while giving CI and release checks a command that cannot report success with missing integration coverage. Container tests use disposable Testcontainers instances; they must not target a developer's persistent database.

Testcontainers' bundled docker-java client currently defaults to Docker API 1.32. The runner first checks Docker availability, reads the daemon's minimum and maximum API versions, then selects at least 1.40 where the daemon supports it. The local OrbStack daemon reports API range 1.40–1.54. `DOCKER_JAVA_API_VERSION` can select another version within the daemon's reported range; the runner passes it as the `api.version` JVM property to its Gradle subprocess and does not modify Docker CLI configuration or persistent user settings. An unavailable daemon fails before Gradle starts.

Coverage artifacts are written to `build/reports/jacoco/test/html/index.html` and `build/reports/jacoco/test/jacocoTestReport.xml`. The report includes production code as-is, so DTO/model getters can dilute the overall percentage. For service-level decisions, inspect the relevant service class and branch coverage in the HTML report alongside the overall total.
