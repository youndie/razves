---
id: B-24
title: "razves resolves by id, out of a repository"
status: done
priority: P1
size: S
stage: stage-2-gradle
epic: feature-size-budget-gate
---

# B-24 — razves resolves by id, out of a repository

Found while starting [B-17](B-17-sborka-convention.md), which is written as though a `sborka`
convention could apply `io.github.youndie.razves` today. It cannot: **razves had never been
published**, and a convention plugin that applies another plugin needs that plugin resolvable on the
build classpath of every repository taking the convention. The item recorded no such prerequisite.

- **The decision and its reason.** Apply `io.github.youndie.sborka.publish` to `gradle-plugin` and
  prove the result is consumable, before anything is built on top of it. `core` already had it;
  `gradle-plugin` did not, so the one artifact a consumer actually names - the plugin marker - did not
  exist.
- `cli` stays unpublished on purpose: it ships as a native executable attached to a release, and
  there is nothing about it for a Maven consumer to resolve.
- **The check is a TestKit project that resolves razves by id**, out of a repository this build
  published into, with no `withPluginClasspath()`. Every other test in that file hands the plugin over
  as a classpath, which proves the code works and nothing about whether the published artifact does.
- The repository is under `build/` rather than `~/.m2`: a test that writes to a developer's local
  repository leaves something behind, and "it worked because your machine already had it" is exactly
  the failure this test exists to catch.

- AC: `io.github.youndie.razves:io.github.youndie.razves.gradle.plugin` exists with a POM naming the
  implementation. **Verified: it does, at 0.1.0, with the licence, developer and URL metadata sborka
  supplies.**
- AC: a project that has never seen this build applies razves by id and gets a report.
  **Verified.**
- Anchors: `gradle-plugin/build.gradle.kts`, `core/build.gradle.kts`,
  `gradle-plugin/src/test/kotlin/io/github/youndie/razves/gradle/SizeReportTaskTest.kt`

**What is still missing** is the remote: [B-25](B-25-publish-razves.md). Publishing works; nothing
has been published anywhere a second machine can reach.
