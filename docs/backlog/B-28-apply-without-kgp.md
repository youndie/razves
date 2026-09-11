---
id: B-28
title: "Applying razves without the Kotlin Gradle Plugin should do nothing, not crash"
status: done
priority: P3
size: S
stage: stage-2-gradle
epic: feature-size-report
---

# B-28 — Applying razves without the Kotlin Gradle Plugin should do nothing, not crash

```
> Failed to apply plugin 'io.github.youndie.razves'.
   > Could not create plugin of type 'RazvesPlugin'.
      > Could not generate a decorated class for type RazvesPlugin.
         > org/jetbrains/kotlin/gradle/plugin/mpp/Executable
```

**Found by a consumer, not by a test.** Every test in
[SizeReportTaskTest](../../gradle-plugin/src/test/kotlin/io/github/youndie/razves/gradle/SizeReportTaskTest.kt)
builds a project that has the Kotlin Gradle Plugin, because that is the only kind of project razves
is useful in - so the one thing none of them can see is what happens when it is absent. A build
that applies razves without KGP on its classpath does not get a no-op; it gets a failure at
decoration time, before any of the guarded code could decline to run.

- **The decision and its reason.** The usage is already guarded -
  [`RazvesPlugin`](../../gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/RazvesPlugin.kt)
  touches KGP only inside `plugins.withId("org.jetbrains.kotlin.multiplatform")`. What is not
  guarded is the *class*: Gradle decorates the plugin type on apply, which resolves the signatures
  of its members, and a lambda over `Executable` is one of them. Moving the KGP-facing wiring into
  a separate class that is only loaded inside the `withId` block is what makes the guard real.
- **How much this matters is honestly small**, which is why it is P3: KGP is on the classpath of
  every build that would want razves, including a `sborka` convention ([B-17](B-17-sborka-convention.md)),
  and the failure is loud rather than silent. It is here because the shape of it - a guard that
  reads correct and is bypassed by the runtime before it is reached - is worth one small fix.

- AC: a plain JVM project with no Kotlin plugin applies razves, gets no size tasks, and builds.
  **Verified by `SizeReportTaskTest.aProjectWithNoKotlinPluginAppliesRazvesAndSimplyGetsNoSizeTasks`,
  which failed with the message above before the fix and passes after it.**
- AC: the same project applies razves *before* the Kotlin plugin and still gets its tasks.
  **Verified by `SizeReportTaskTest.razvesAppliedBeforeTheKotlinPluginStillGetsItsTasks`.**
- Anchors: `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/RazvesPlugin.kt`,
  `gradle-plugin/src/main/kotlin/io/github/youndie/razves/gradle/NativeBinaries.kt`

**Moving `register` alone would not have been enough.** The member with `Executable` in its signature
was the obvious half; the other half is that a lambda compiles to a synthetic method of the class it
is written in, so `all { binary -> … }` left an `Executable` parameter in `RazvesPlugin` no matter
where the body went. What the fix does is make the class name no Kotlin type at all: the whole
wiring is `NativeBinaries`, named only inside the `withId` block and therefore loaded only when the
Kotlin plugin is there.

**Both tests resolve razves by id from the repository this build publishes into, and they have to.**
Every other test in that file hands the plugin over with `withPluginClasspath()`, whose
plugin-under-test metadata carries KGP - so the class resolves there whatever razves does, and the
defect is invisible. A test written the usual way would have passed before the fix.
