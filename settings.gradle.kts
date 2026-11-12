// This file tells Gradle which modules make up the Tallyhook project.
rootProject.name = "tallyhook"

include(
    "common",
    "ledger-core",
    "api",
    "outbox",
    "webhooks",
    "reconciliation",
)
