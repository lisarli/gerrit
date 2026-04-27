load("//tools/bzl:junit.bzl", "junit_tests")

junit_tests(
    name = "schema_tests",
    size = "small",
    srcs = ["JdbcAccountPatchLineReviewStoreTest.java"],
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/gerrit/entities",
        "//java/com/google/gerrit/extensions:api",
        "//java/com/google/gerrit/server",
        "//java/com/google/gerrit/server/schema",
        "//java/com/google/gerrit/exceptions",
        "//lib:guava",
        "//lib:h2",
        "//lib/commons:dbcp",
        "//lib/guice",
        "//lib/truth",
    ],
)
