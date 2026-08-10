<br />

Android Gradle plugin 9.0 is a major release that brings API and behavior changes.

To update to Android Gradle plugin 9.0.1, use the [Android Gradle plugin Upgrade Assistant](https://developer.android.com/build/agp-upgrade-assistant). The AGP upgrade assistant helps preserve existing behaviors when upgrading your project whenever appropriate, so you can upgrade your project to use AGP 9.0 even if you're not ready to adopt all the new defaults in AGP 9.0.

There are also two agent skills available to make the upgrade process easier. For a non-KMP app, try the [AGP 9 upgrade skill](https://github.com/android/skills/tree/main/build/agp/agp-9-upgrade) from the Android skills repository. For a KMP app, try the [AGP 9 upgrade skill](https://github.com/Kotlin/kotlin-agent-skills/tree/main/skills/kotlin-tooling-agp9-migration) from JetBrains. For more information about using skills in Android Studio, see [Extend Agent Mode with skills](https://developer.android.com/studio/gemini/skills).

## Compatibility

The maximum API level that Android Gradle plugin 9.0 supports is API level 36.1. Here is other compatibility info:

<br />

|   | Minimum version | Default version | Notes |
|---:|:---:|:---:|:---:|
| Gradle | 9.1.0 | 9.1.0 | To learn more, see [updating Gradle](https://developer.android.com/build/releases/gradle-plugin?buildsystem=ndk-build#updating-gradle). |
| SDK Build Tools | 36.0.0 | 36.0.0 | [Install](https://developer.android.com/studio/intro/update#sdk-manager) or [configure](https://developer.android.com/tools/releases/build-tools) SDK Build Tools. |
| NDK | N/A | 28.2.13676358 | [Install](https://developer.android.com/studio/projects/install-ndk#specific-version) or [configure](https://developer.android.com/studio/projects/install-ndk#apply-specific-version) a different version of the NDK. |
| JDK | 17 | 17 | To learn more, see [setting the JDK version](https://developer.android.com/studio/intro/studio-config#jdk). |

<br />

## The `android` DSL classes now only implement the new public interfaces

Over the last several years, we have introduced [new interfaces](https://developer.android.com/reference/tools/gradle-api) for our DSL and API in order to better control which APIs are public. AGP versions 7.x and 8.x still used the old DSL types (for example `BaseExtension`) which also implemented the new public interfaces, in order to maintain compatibility as work progressed on the interfaces.

AGP 9.0 uses our new DSL interfaces exclusively, and the implementations have changed to new types that are fully hidden. This also removes access to the old, deprecated variant API.

To update to AGP 9.0, you might need to do the following:

- **Ensure your project is compatible with [built-in Kotlin](https://developer.android.com/build/releases/agp-9-0-0-release-notes#android-gradle-plugin-built-in-kotlin):** The `org.jetbrains.kotlin.android` plugin is not compatible with the new DSL.
- **Switch KMP projects to the [Android Gradle Library Plugin for KMP](https://developer.android.com/kotlin/multiplatform/plugin):** Using the `org.jetbrains.kotlin.multiplatform` plugin in the same Gradle subproject as the `com.android.library` and `com.android.application` plugins is not compatible with the new DSL.

  > [!NOTE]
  > **Note:** The new KMP integration does not support using KMP and the Android Application plugin in the same Gradle subproject. To migrate, extract your Android app to a separate subproject.

- **Update your build files:** While the change of interfaces is meant to keep the DSL as similar as possible, there might be [some small changes](https://developer.android.com/build/releases/agp-9-0-0-release-notes#android-gradle-plugin-changed-dsl).

- **Update your custom build logic to reference the new DSL and API:** Replace any references to the internal DSL with the public DSL interfaces. In most cases this will be a one-to-one replacement. Replace any use of the `applicationVariants` and similar APIs with the new [`androidComponents` API](https://developer.android.com/build/extend-agp#variant-api-artifacts-tasks). This might be more complex, as the `androidComponents` API is designed to be more stable to keep plugins compatible longer. Check our [Gradle Recipes](https://github.com/android/gradle-recipes/tree/agp-9.0) for examples.

- **Update third-party plugins:** Some third-party plugins might still depend on interfaces or APIs that are no longer exposed. Migrate to versions of those plugins which are compatible with AGP 9.0.

The switch to the new DSL interfaces prevents plugins and Gradle build scripts using various deprecated APIs, including:

| Deprecated API in the `android` block | Function | Replacement |
|---|---|---|
| `applicationVariants`, `libraryVariants`, `testVariants`, and `unitTestVariants` | Extension points for plugins to add new functionality to AGP. | Replace this with the [`androidComponents.onVariants`](https://developer.android.com/reference/tools/gradle-api/9.0/com/android/build/api/variant/AndroidComponentsExtension#onVariants(com.android.build.api.variant.VariantSelector,kotlin.Function1)) API, for example: ```kotlin androidComponents { onVariants() { variant -> variant.signingConfig .enableV1Signing.set(false) } } ``` There might not be a direct replacement for all previous APIs. [File an issue](https://developer.android.com/studio/report-bugs) if there is a use case that is not covered by the new variant APIs. |
| `variantFilter` | Allows selected variants to be disabled. | Replace this with the [`androidComponents.beforeVariants`](https://developer.android.com/reference/tools/gradle-api/9.0/com/android/build/api/variant/AndroidComponentsExtension#beforeVariants(com.android.build.api.variant.VariantSelector,kotlin.Function1))) API, for example: ```kotlin androidComponents { beforeVariants( selector() .withBuildType("debug") .withFlavor("color", "blue") ) { variantBuilder -> variantBuilder.enable = false } } ``` |
| `deviceProvider` and `testServer` | Registration of custom test environments for running tests against Android devices and emulators. | Switch to [Gradle-managed devices](https://developer.android.com/studio/test/gradle-managed-devices). |
| `sdkDirectory`, `ndkDirectory`, `bootClasspath`, `adbExecutable`, and `adbExe` | Using various components of the Android SDK for custom tasks. | Switch to [`androidComponents.sdkComponents`](https://developer.android.com/reference/tools/gradle-api/9.0/com/android/build/api/dsl/SdkComponents). |
| `registerArtifactType`, `registerBuildTypeSourceProvider`, `registerProductFlavorSourceProvider`, `registerJavaArtifact`, `registerMultiFlavorSourceProvider`, and `wrapJavaSourceSet` | Obsolete functionality mostly related to the handling of generated sources in Android Studio, which stopped working in AGP 7.2.0. | There is no direct replacement for these APIs. |
| `dexOptions` | Obsolete settings related to the `dx` tool, which has been replaced by [`d8`](https://developer.android.com/tools/d8). None of the settings have had any effect since Android Gradle plugin 7.0. | There is no direct replacement. |
| `generatePureSplits` | Generate configuration splits for instant apps. | The ability to ship configuration splits is now built in to Android app bundles. |
| `aidlPackagedList` | AIDL files to package in the AAR to expose it as API for libraries and apps that depend on this library. | This is still exposed on [`LibraryExtension`](https://developer.android.com/reference/tools/gradle-api/9.0/com/android/build/api/dsl/LibraryExtension) but not on the other extension types. |

If you update to AGP 9.0 and see the following error message, it means that your project is still referencing some of the old types:

    java.lang.ClassCastException: class com.android.build.gradle.internal.dsl.ApplicationExtensionImpl$AgpDecorated_Decorated
    cannot be cast to class com.android.build.gradle.BaseExtension

If you are blocked by incompatible third-party plugins, you can opt out and get back the old implementations for the DSL, as well as the old variant API. While doing this, the new interfaces are also available, and you can still update your own build logic to the new API. To opt out, include this line in your `gradle.properties` file:

    android.newDsl=false

Alternatively, for a more gradual migration, AGP 9.4 lets you opt out individual modules. To learn how, see [Variant API module opt-out](https://developer.android.com/build/releases/agp-9-4-0-release-notes#new-dsl-opt-out).

The previous classes are marked as deprecated in AGP 9.0. This means projects that opt out of the `newDsl` flag will see deprecation warnings, including on the `android` block itself.

> [!CAUTION]
> **Caution:** The ability to opt-out will be removed in AGP 10.0 (mid-2026).

You can also start upgrading to the new APIs before upgrading to AGP 9.0. The new interfaces have been present for many AGP versions and so you can have a mix of new and old. The [AGP API reference docs](https://developer.android.com/reference/tools/gradle-api) show the API surface for each AGP version, and when each class, method and field was added.

We're reaching out to the authors of commonly used plugins to help them adapt and release plugins that are fully compatible with the new modes, and will continue to enhance the AGP Upgrade Assistant in Android Studio to guide you through the migration.

If you find that the new DSL or Variant API are missing capabilities or features, please file an [issue](https://issuetracker.google.com/issues/new?component=192708&template=840533) as soon as possible.

## Built-in Kotlin

Android Gradle plugin 9.0 introduces built-in Kotlin support and enables it by default. That means you no longer have to apply the `org.jetbrains.kotlin.android` (or `kotlin-android`) plugin in your build files to compile Kotlin source files. This simplifies the Kotlin integration with AGP, avoids the use of deprecated APIs, and improves performance in some cases.

Therefore, when you upgrade your project to AGP 9.0, you need to also [migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) or [opt out](https://developer.android.com/build/migrate-to-built-in-kotlin#opt-out-of-built-in-kotlin).

You can also [selectively disable built-in Kotlin support](https://developer.android.com/build/migrate-to-built-in-kotlin#selectively-disable) for Gradle subprojects that don't have Kotlin sources.

## Runtime dependency on Kotlin Gradle plugin

To provide [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) support, Android Gradle plugin 9.0 now has a runtime dependency on Kotlin Gradle plugin (KGP) 2.2.10. That means you no longer have to declare a KGP version, and if you use a KGP version lower than 2.2.10, Gradle will automatically upgrade your KGP version to 2.2.10. Likewise, if you use a KSP version lower than 2.2.10-2.0.2, AGP will upgrade it to 2.2.10-2.0.2 to match the KGP version.

### Upgrade to a higher KGP version

To use a higher version of KGP or KSP, add the following to your top-level build file:

    buildscript {
        dependencies {
            // For KGP
            classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:KGP_VERSION")

            // For KSP
            classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:KSP_VERSION")
        }
    }

### Downgrade to a lower KGP version

You can only downgrade the KGP version if you've [opted out of built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin#opt-out-of-built-in-kotlin). This is because AGP 9.0 enables built-in Kotlin by default, and built-in Kotlin requires KGP 2.2.10 or higher.

To use a lower version of KGP or KSP, declare that version in your top-level build file using a [strict version](https://docs.gradle.org/current/userguide/dependency_versions.html#sec:strict-version) declaration:

    buildscript {
        dependencies {
            // For KGP
            classpath("org.jetbrains.kotlin:kotlin-gradle-plugin") {
                version { strictly("KGP_VERSION") }
            }

            // For KSP
            classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin") {
                version { strictly("KSP_VERSION") }
            }
        }
    }

Note that the minimum KGP version you can downgrade to is 2.0.0.

> [!CAUTION]
> **Caution:** When you downgrade the KGP version, your project might not be compatible with future minor releases of AGP 9, and it might not work with test fixtures.

## IDE support for test fixtures

AGP 9.0 brings full Android Studio IDE support for [test fixtures](https://developer.android.com/reference/tools/gradle-api/9.0/com/android/build/api/dsl/TestFixtures).

## Fused Library Plugin

The Fused Library Plugin (Preview) lets you publish multiple libraries as a single Android Library AAR. This can make it easier for your users to depend on your published artifacts.

For information about getting started, see [Publish multiple Android libraries as one with Fused Library](https://developer.android.com/build/publish-library/fused-library).

## Behavior changes

Android Gradle plugin 9.0 has the following new behaviors:

| Behavior | Recommendation |
|---|---|
| Android Gradle plugin 9.0 uses NDK version `r28c` by default. | Consider specifying the NDK version you want to use explicitly. |
| Android Gradle plugin 9.0 by default requires consumers of a library to use the same or higher compile SDK version. | Use the same or higher compile SDK when consuming a library. If this is not possible, or you want to give consumers of a library you publish more time to switch, set [`AarMetadata.minCompileSdk`](https://developer.android.com/reference/tools/gradle-api/9.0/com/android/build/api/dsl/AarMetadata#minCompileSdk()) explicitly. |

AGP 9.0 includes updates to the following Gradle properties' defaults. This gives you the choice to preserve the AGP 8.13 behavior when upgrading:

| Property | Function | Change from AGP 8.13 to AGP 9.0 | Recommendation |
|---|---|---|---|
| `android.newDsl` | Use the new DSL interfaces, without exposing the legacy implementations of the `android` block. This also means the legacy variant API, such as `android.applicationVariants` is no longer accessible. | `false` â†’ `true` | You can opt out by setting `android.newDsl=false`. Once all plugins and build logic your project uses are compatible, remove the opt out. |
| `android.builtInKotlin` | Enables [built-in Kotlin](https://developer.android.com/build/releases/agp-9-0-0-release-notes#android-gradle-plugin-built-in-kotlin) | `false` â†’ `true` | [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) if you can or [opt out](https://developer.android.com/build/migrate-to-built-in-kotlin#opt-out-of-built-in-kotlin). |
| `android.uniquePackageNames` | Enforces that each library has a distinct package name. | `false` â†’ `true` | Specify uniqïy¶‰žËkºwµça•­A…É…µ•Ñ•É%Í9½Ñ9Õ±°¡©…Ù„¹±…¹œ¹=‰©•Ð°©…Ù„¹±…¹œ¹MÑÉ¥¹œ¤ì(€€€€€Ù½¥¡•­9½Ñ9Õ±±A…É…µ•Ñ•È¡©…Ù„¹±…¹œ¹=‰©•Ð°©…Ù„¹±…¹œ¹MÑÉ¥¹œ¤ì(€€€ô()Q¡”½ÁÑ¥½¸Ù…±Õ•Ì°½É‘•É•™É½´Ñ¡”Ý•…­•ÍÐÑ¼Ñ¡”ÍÑÉ½¹•ÍÐ°¡…Ù”Ñ¡”™½±±½Ý¥¹œ•™™•Ðè((´­••Á€‘½•Í¸Ð¡…¹”Ñ¡”¡•­Ì¸(´É•µ½Ù•}µ•ÍÍ…•€É•ÝÉ¥Ñ•Ì•… ¡•¬µ•Ñ¡½…±°Ñ¼„…±°Ñ¼•Ñ±…ÍÌ ¥€½¸Ñ¡”™¥ÉÍÐ…ÉÕµ•¹Ð½˜Ñ¡”…±°€¡•™™•Ñ¥Ù•±ä­••Á¥¹œÑ¡”¹Õ±°¡•¬°‰ÕÐÝ¥Ñ¡½ÕÐ…¹äµ•ÍÍ…”¤¸(´É•µ½Ù•€½µÁ±•Ñ•±äÉ•µ½Ù•ÌÑ¡”¡•­Ì¸()	ä‘•™…Õ±ÐHàÕÍ•ÌÉ•µ½Ù•}µ•ÍÍ…•€¸¹äÍÁ•¥™¥…Ñ¥½¸½˜€µÁÉ½•ÍÍ­½Ñ±¥¹¹Õ±±¡•­Í€Ý¥±°½Ù•ÉÉ¥‘”Ñ¡…Ð¸%˜ÍÁ•¥™¥•µÕ±Ñ¥Á±”Ñ¥µ•ÌÑ¡”ÍÑÉ½¹•ÍÐÙ…±Õ”¥ÌÕÍ•¸((ŒŒŒMÑ½ÀÁÉ½Á……Ñ¥¹œ­••À¥¹™¼Ñ¼½µÁ…¹¥½¸µ•Ñ¡½‘Ì()]¡•¸­••ÀÉÕ±•Ìµ…Ñ ¥¹Ñ•É™…”µ•Ñ¡½‘ÌÑ¡…Ð…É”ÍÕ‰©•ÐÑ¼‘•ÍÕ…É¥¹œ°HàÁÉ•Ù¥½ÕÍ±ä¥¹Ñ•É¹…±±äÑÉ…¹Í™•ÉÉ•Ñ¡”€©‘¥Í…±±½Ü½ÁÑ¥µ¥é…Ñ¥½¸¨…¹€©‘¥Í…±±½ÜÍ¡É¥¹­¥¹œ¨‰¥ÑÌÑ¼Ñ¡”Íå¹Ñ¡•Í¥é•½µÁ…¹¥½¸µ•Ñ¡½‘Ì¸()MÑ…ÉÑ¥¹œÝ¥Ñ @€ä¸À°­••ÀÉÕ±•Ì¹¼±½¹•È…ÁÁ±äÑ¼½µÁ…¹¥½¸µ•Ñ¡½‘Ì¸Q¡¥Ì¥Ì½¹Í¥ÍÑ•¹ÐÝ¥Ñ Ñ¡”™…ÐÑ¡…Ð­••ÀÉÕ±•Ì…É”¹½Ð…ÁÁ±¥…‰±”Ñ¼½Ñ¡•È½µÁ¥±•ÈÍå¹Ñ¡•Í¥é•™¥•±‘Ì½µ•Ñ¡½‘Ì½±…ÍÍ•Ì¸()	äÑÉ…¹Í™•ÉÉ¥¹œÑ¡”€©‘¥Í…±±½Ü½ÁÑ¥µ¥é…Ñ¥½¸¨…¹€©‘¥Í…±±½ÜÍ¡É¥¹­¥¹œ¨‰¥ÑÌÑ¼Ñ¡”½µÁ…¹¥½¸µ•Ñ¡½‘Ì°Ñ¡”™½±±½Ý¥¹œÕÍ”…Í”Ý…ÌÁÉ•Ù¥½ÕÍ±äÍÕÁÁ½ÉÑ•è((Ä¸½µÁ¥±”„±¥‰É…ÉäÝ¥Ñ ‘•™…Õ±Ñ€½ÍÑ…Ñ¥€½ÁÉ¥Ù…Ñ•€¥¹Ñ•É™…”µ•Ñ¡½‘ÌÑ¼`Ý¥Ñ µ¥¹M‘­€pð€ÈÐ…¹ÉÕ±•ÌÑ¡…Ð­••ÀÑ¡”¥¹Ñ•É™…”µ•Ñ¡½‘Ì¸(È¸½µÁ¥±”…¸…ÁÀÝ¥Ñ Ñ¡”±¥‰É…Éä½¸±…ÍÍÁ…Ñ …¹€µ…ÁÁ±åµ…ÁÁ¥¹€¸(Ì¸5•É”Ñ¡”…ÁÀ…¹Ñ¡”±¥‰É…Éä¸()9½Ñ”Ñ¡…ÐÑ¡¥Ì½¹±äÝ½É­ÌÝ¥Ñ €µ…ÁÁ±åµ…ÁÁ¥¹€Í¥¹”Ñ¡”‘¥Í…±±½Ü½‰™ÕÍ…Ñ¥½¹€‰¥Ð¥Ì¹½ÐÑÉ…¹Í™•ÉÉ•Ñ¼Ñ¡”½µÁ…¹¥½¸µ•Ñ¡½‘Ì´´µÑ¡…Ð¥Ì°Ñ¡”½µÁ…¹¥½¸±…ÍÍ•Ì•¹•É…Ñ•™É½´ÍÑ•À€ÄÝ½Õ±¡…Ù”½‰™ÕÍ…Ñ•µ•Ñ¡½¹…µ•Ì¸()½¥¹œ™½ÉÝ…ÉÑ¡¥ÌÕÍ”…Í”¥Ì¹¼±½¹•ÈÍÕÁÁ½ÉÑ•™½Èµ¥¹M‘­€pð€ÈÐ¸Q¡”Ý½É­…É½Õ¹¥ÌÑ¼‘¼Ñ¡”™½±±½Ý¥¹œè((Ä¸•ÍÕ…ÈÑ¡”±¥‰É…ÉäÝ¥Ñ ‘•™…Õ±Ñ€½ÍÑ…Ñ¥€½ÁÉ¥Ù…Ñ•€¥¹Ñ•É™…”µ•Ñ¡½‘ÌÑ¼±…ÍÌ™¥±•ÌÝ¥Ñ µ¥¹M‘­€pð€ÈÐ¸(È¸½µÁ¥±”Ñ¡”‘•ÍÕ…É•…ÉÑ¥™…ÐÕÍ¥¹œHà…¹ÉÕ±•ÌÑ¡…Ð­••ÀÑ¡”¥¹Ñ•É™…”µ•Ñ¡½‘Ì½¸Ñ¡”½µÁ…¹¥½¸±…ÍÍ•Ì¸(Ì¸½µÁ¥±”Ñ¡”…ÁÀÝ¥Ñ Ñ¡”±¥‰É…Éä½¸±…ÍÍÁ…Ñ ¸(Ð¸5•É”Ñ¡”…ÁÀ…¹Ñ¡”‘•ÍÕ…É•…ÉÑ¥™…Ð¸()¹½Ñ¡•ÈÍ¥‘”•™™•Ð½˜Ñ¡¥Ì¥ÌÑ¡…Ð¥Ð¥Ì¹¼±½¹•ÈÁ½ÍÍ¥‰±”Ñ¼­••ÀÑ¡”¥¹¹•È±…ÍÌ…¹•¹±½Í¥¹œµ•Ñ¡½…ÑÑÉ¥‰ÕÑ•Ì™½È…¹½¹åµ½ÕÌ…¹±½…°±…ÍÍ•Ì¥¹Í¥‘”¥¹Ñ•É™…”½µÁ…¹¥½¸µ•Ñ¡½‘Ì¸((ŒŒŒ¡…¹”Ñ¡”‘•™…Õ±Ð•µ¥ÑÑ•Í½ÕÉ”™¥±”Ñ¼Èàµµ…Àµ¥´ñ5A}%ù€()Q¡¥Ì¡…¹”¥Ì¥¸@ÍÑ…ÉÑ¥¹œ™É½´€à¸ÄÈ¸À¸()Q¡”‘•™…Õ±Ð•µ¥ÑÑ•Í½ÕÉ”™¥±”…ÑÑÉ¥‰ÕÑ”™½È„±…ÍÌ¡…¹•Ì™É½´M½ÕÉ•¥±•€Ñ¼Èàµµ…Àµ¥´ñ5A}%ù€Ý¡•¸É•ÑÉ…¥¹œ¥ÌÉ•ÅÕ¥É•€¡Ñ¡…Ð¥Ì°Ý¡•¸•¥Ñ¡•È½‰™ÕÍ…Ñ¥½¸½È½ÁÑ¥µ¥é…Ñ¥½¸¥Ì•¹…‰±•¤¸()¥Ù•¸…¸½‰™ÕÍ…Ñ•ÍÑ…¬ÑÉ…”°Ñ¡”¹•ÜÍ½ÕÉ”™¥±”…ÑÑÉ¥‰ÕÑ”µ…­•Ì¥ÐÁ½ÍÍ¥‰±”Ñ¼•áÑÉ…ÐÑ¡”%½˜Ñ¡”µ…ÁÁ¥¹œ™¥±”Ñ¡…Ð¥ÌÉ•ÅÕ¥É•™½ÈÉ•ÑÉ…¥¹œ°Ý¡¥ …¸‰”ÕÍ•Ñ¼ÍÕÁÁ½ÉÐm…ÕÑ½µ…Ñ•É•ÑÉ…¥¹œ½˜ÍÑ…¬ÑÉ…•Ì¥¸1½…Ñt¡¡ÑÑÁÌè¼½‘•Ù•±½Á•È¹…¹‘É½¥¹½´½ÍÑÕ‘¥¼½ÁÉ•Ù¥•Ü½™•…ÑÕÉ•Ì±½…ÐµÉ•ÑÉ…”¤¸()%˜„ÕÍÑ½´Í½ÕÉ”™¥±”…ÑÑÉ¥‰ÕÑ”¥ÌÕÍ•€¡€µÉ•¹…µ•Í½ÕÉ•™¥±•…ÑÑÉ¥‰ÕÑ•€¤Ñ¡¥ÌÕÍÑ½´Í½ÕÉ”™¥±”…ÑÑÉ¥‰ÕÑ”½¹Ñ¥¹Õ•ÌÑ¼Ñ…­”ÁÉ••‘•¹”¸()%¸AÉ½Õ…É½µÁ…Ñ¥‰¥±¥Ñäµ½‘”€¡Ý¡•¸É…‘±”¹ÁÉ½Á•ÉÑ¥•Í€½¹Ñ…¥¹Ì…¹‘É½¥¹•¹…‰±•Hà¹™Õ±±5½‘”õ™…±Í•€¤°•µ¥ÑÑ¥¹œ„Í½ÕÉ”™¥±”…ÑÑÉ¥‰ÕÑ”½˜Èàµµ…Àµ¥´ñ5A}%ù€½¹±äÑ…­•Ì•™™•Ð¥˜Ñ¡”M½ÕÉ•¥±•€…ÑÑÉ¥‰ÕÑ”¥Ì€©¹½Ð¨­•ÁÐ¸ÁÁÌÑ¡…ÐÕÍ”AÉ½Õ…É½µÁ…Ñ¥‰¥±¥Ñäµ½‘”…¹Ý…¹ÐÑ¼¥¹±Õ‘”Ñ¡”µ…ÁÁ¥¹œ™¥±”%¥¸Ñ¡•¥ÈÍÑ…¬ÑÉ…•ÌÍ¡½Õ±É•µ½Ù”€µ­••Á…ÑÑÉ¥‰ÕÑ•ÌM½ÕÉ•¥±•€€¡½Èµ¥É…Ñ”Ñ¼Hà™Õ±°µ½‘”¤¸()Q¡”µ…À%ÕÍ•¥¸Èàµµ…Àµ¥´ñ5A}%ù€¥ÌÑ¡”™Õ±°µ…À¡…Í °…¹¹½Ð„€Ü¡…É…Ñ•ÈÁÉ•™¥à½˜Ñ¡”µ…À¡…Í Ý¡¥ Ý…ÌÁÉ•Ù¥½ÕÍ±äÕÍ•¸((ŒŒŒ¹…‰±”ÕÍ”½˜µ¥¹¥µ¥é•Íå¹Ñ¡•Ñ¥Œ¹…µ•Ì¥¸0à‘•ÍÕ…É¥¹œ()Q¡”¹…µ”½˜Íå¹Ñ¡•Ñ¥Œ±…ÍÍ•Ì•¹•É…Ñ•‰äà¹½Éµ…±±ä½¹Ñ…¥¹ÌÑ¡”ÍÕ‰ÍÑÉ¥¹œ€‘áÑ•É¹…±Må¹Ñ¡•Ñ¥€Ñ¡…ÐÑ•±±Ìå½ÔÑ¡…ÐÑ¡¥Ì¥Ì„Íå¹Ñ¡•Ñ¥Œ•¹•É…Ñ•‰äà¸5½É•½Ù•È°Ñ¡”¹…µ”½˜Ñ¡”Íå¹Ñ¡•Ñ¥Œ…±Í¼•¹½‘•ÌÑ¡”Íå¹Ñ¡•Ñ¥Œ­¥¹€¡™½È•á…µÁ±”°	…­Á½ÉÑ€°1…µ‰‘…€¤¸Q¡¥Ì¡…Ì„¹•…Ñ¥Ù”¥µÁ…Ð½¸Ñ¡”É•ÍÕ±Ñ¥¹œ`Í¥é”°Í¥¹”Ñ¡”±…ÍÌ¹…µ•ÌÑ…­”ÕÀµ½É”ÍÁ…”¥¸Ñ¡”ÍÑÉ¥¹œÁ½½°¸()@€ä¸À½¹™¥ÕÉ•Ì0à€¡½É”±¥‰É…Éä‘•ÍÕ…É¥¹œ¤Í¼Ñ¡…ÐÑ¡”`™¥±”½¹Ñ…¥¹¥¹œ…±°¨‘€±…ÍÍ•ÌÕÍ•Ì„¹•ÜÍ¡½ÉÑ•¹•±…ÍÌ¹…µ”™½Éµ…Ð™½ÈÍå¹Ñ¡•Ñ¥Œ±…ÍÍ•Ì¸Q¡”¹•Ü±…ÍÌ¹…µ”ÕÍ•Ì„¹Õµ•É¥Œ%€¡™½È•á…µÁ±”°€Å€¤¸((ŒŒŒI•µ½Ù”ÍÕÁÁ½ÉÐ™½È€µ…‘‘½¹™¥ÕÉ…Ñ¥½¹‘•‰Õ¥¹€()@€ä¸ÀÉ•µ½Ù•ÌÍÕÁÁ½ÉÐ™½È€µ…‘‘½¹™¥ÕÉ…Ñ¥½¹‘•‰Õ¥¹€¸Q¡”½µÁ¥±•È¹½ÜÉ•Á½ÉÑÌ„Ý…É¹¥¹œ¥˜Ñ¡”™±…œ¥ÌÕÍ•¸((ŒŒŒI•µ½Ù”ÍÕÁÁ½ÉÐ™½È•¹•É…Ñ¥¹œ0àÉÕ±•Ì™É½´à½Hà()Q¡¥Ì¡…¹”¥Ì½¹±äÉ•±•Ù…¹Ð™½È‘•Ù•±½Á•ÉÌÕÍ¥¹œÑ¡”à½Hà½µµ…¹±¥¹”½ÈA%Ì‘¥É•Ñ±ä¸()Hà€ä¸ÀÉ•µ½Ù•ÌÍÕÁÁ½ÉÐ™½È•¹•É…Ñ¥¹œ­••ÀÉÕ±•Ì™½È0à™É½´à…¹Hà¸e½ÔÍ¡½Õ±¥¹ÍÑ•…ÕÍ”QÉ…•I•™•É•¹•Í€™½ÈÑ¡¥ÌÁÕÉÁ½Í”¸()5½É”ÍÁ•¥™¥…±±ä°Ñ¡”µ•Ñ¡½‘Ìá½µµ…¹¹‰Õ¥±‘•È¹Í•Ñ•ÍÕ…É•‘1¥‰É…Éå-••ÁIÕ±•½¹ÍÕµ•É€…¹Há½µµ…¹¹	Õ¥±‘•È¹Í•Ñ•ÍÕ…É•‘1¥‰É…Éå-••ÁIÕ±•½¹ÍÕµ•É€…É”É•µ½Ù•°…¹Ñ¡”ÍÕÁÁ½ÉÐ™½È€´µ‘•ÍÕ…É•µ±¥ˆµÁœµ½¹˜µ½ÕÑÁÕÑ€¥ÌÉ•µ½Ù•™É½´Ñ¡”½µµ…¹±¥¹”½ÁÑ¥½¹Ì½˜à…¹Hà¸((ŒŒ¥á•¥ÍÍÕ•Ì((ŒŒŒ¹‘É½¥É…‘±”Á±Õ¥¸€ä¸À¸À()¥á•%ÍÍÕ•Ì€¨©¹‘É½¥É…‘±”A±Õ¥¸¨¨m%ÍÍÕ”€ŒÄÜÄÈäÌÜÄÉt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÄÜÄÈäÌÜÄÈ¤•…ÑÕÉ”I•ÅÕ•ÍÐè%¹©•Ð¥‘•…°@Ù•ÉÍ¥½¸…Ì„ÁÉ½Á•ÉÑäm%ÍÍÕ”€ŒÐÐÌäÜØÔÌÍt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÌäÜØÔÌÌ¤MÑ…‰¥±¥é”M¥¹±•ÉÑ¥™…Ð¹YIM%=9}=9QI=1}%9=}%1m%ÍÍÕ”€ŒÈÈÌØÐÌÔÀÙt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÈÈÌØÐÌÔÀØ¤…¹‘É½¥‘Q•ÍÐ½¹¹•Ñ•‘¡•¬±½…Ð½ÕÑÁÕÐ¥Ì‰É½­•¸m%ÍÍÕ”€ŒÌàØÈÈÄÀÜÁt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌàØÈÈÄÀÜÀ¤	Õ¥±Ðµ¥¸-½Ñ±¥¸ÍÕÁÁ½ÉÐ¥¸@Í¡½Õ±¹½ÐÍå¹¡É½¹¥é”Ý¥Ñ Ñ¡”-½Ñ±¥¸Í½ÕÉ•Í•ÑÌm%ÍÍÕ”€ŒÐØÀÀäÐàÀÉt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐØÀÀäÐàÀÈ¤µ¥ÍÍ¥¹¥µ•¹Í¥½¹MÑÉ…Ñ•äÁÉ•™•ÉÌ„™±…Ù½Èµ…¡¥¹œ¥ÑÌ½Ý¸¹…µ”•Ù•¸™É½´…¸Õ¹É•±…Ñ•‘¥µ•¹Í¥½¸m%ÍÍÕ”€ŒÌàØÈÈÄÀÜÁt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌàØÈÈÄÀÜÀ¤	Õ¥±Ðµ¥¸-½Ñ±¥¸ÍÕÁÁ½ÉÐ¥¸@Í¡½Õ±¹½ÐÍå¹¡É½¹¥é”Ý¥Ñ Ñ¡”-½Ñ±¥¸Í½ÕÉ•Í•ÑÌm%ÍÍÕ”€ŒÐÜÄÐÄÀÌÌÙt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÜÄÐÄÀÌÌØ¤@€ä¸À¸ÀµÉŒÀÄ‘½•Í¸ÐÉ•Í½±Ù”-½Ñ±¥¸±¥‰É…É¥•ÌÙ¥„­½Ñ±¥¸ ¤™Õ¹Ñ¥½¸m%ÍÍÕ”€ŒÐÔÀàÔÄÐØÕt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÔÀàÔÄÐØÔ¤	Õ¥±Ðµ¥¸µ­½Ñ±¥¸‘½•Ì¹½ÐÁÕ‰±¥Í ­½Ñ±¥¸µÍÑ‘±¥ˆ‘•Á•¹‘•¹ä½¹ÍÑÉ…¥¹Ð¥¸µ…Ù•¸Á½´m%ÍÍÕ”€ŒÐÈÀÔäÈÈàát¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÈÀÔäÈÈàà¤‘„Ñ•ÍÐ…Í”™½È‘¥Ù•É•¹”‰•ÑÝ••¸½µÁ¥±•M‘¬…¹Ñ…É•ÑM‘¬m%ÍÍÕ”€ŒÐÐäÄÔÌÀÀÑt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐäÄÔÌÀÀÐ¤•µÁÑäÉ•Í½¹™¥ÌÙ…±Õ”±•…‘ÌÑ¼½‰ÍÕÉ”……ÁÐ•ÉÉ½Èm%ÍÍÕ”€ŒÐÐÜÌÜÔäÈÅt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÜÌÜÔäÈÄ¤AÕÉ”)…Ù„ÁÉ½©•Ð¡…Ù”‘•Á•¹‘•¹ä½¸­½Ñ±¥¸ÍÑ‘±¥ˆ¸m%ÍÍÕ”€ŒÌØàØÀÀÜÀÑt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌØàØÀÀÜÀÐ¤I•µ½Ù”‘•ÁÉ•…Ñ•-½Ñ±¥¹5Õ±Ñ¥Á±…Ñ™½Éµ¹‘É½¥‘½µÁ¥±…Ñ¥½¹	Õ¥±‘•ÈÁÉ½Á•ÉÑ¥•Ì¥¸@€ä¸Àm%ÍÍÕ”€ŒÐÐÔÈÀäÌÀåt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÔÈÀäÌÀä¤q½´¹…¹‘É½¥¹Ñ½½±Ì¹‰Õ¥±éÉ…‘±”èä¸À¸Àµ…±Á¡„ÀÕq€Í¡½Õ±¡…Ù”…¸…Á¤‘•Á•¹‘•¹ä½¸-@…¹É…‘±”µ…Á¤m%ÍÍÕ”€ŒÐÔÈØÐÔÜÜåt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÔÈØÐÔÜÜä¤I•¹…µ”½´¹…¹‘É½¥¹•áÁ•É¥µ•¹Ñ…°¹‰Õ¥±Ðµ¥¸µ­½Ñ±¥¸É…‘±”Á±Õ¥¸m%ÍÍÕ”€ŒÐÐØÈÈÀÐÐát¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐØÈÈÀÐÐà¤@äèqÙ…É¥…¹Ð¹Í½ÕÉ•Ì¹­½Ñ±¥¸„„¹…‘‘•¹•É…Ñ•‘M½ÕÉ•¥É•Ñ½Éä ¥q€¥Ì¹½ÐÝ½É­¥¹œm%ÍÍÕ”€ŒÐÐàÐÔÀÜÜÅt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐàÐÔÀÜÜÄ¤…Èµ•Ñ…‘…Ñ„¡•­Ì½¸½µÁ¥±”M‘¬ÕÍ•ÌÑ¡”½±M0m%ÍÍÕ”€ŒÐÐÄÔÈÌÐÐát¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÄÔÈÌÐÐà¤I•µ½Ù”‘•ÁÉ•…Ñ•q½´¹…¹‘É½¥¹‰Õ¥±¹…Á¤¹‘Í°¹5…¹…•‘•Ù¥•Ì¹‘•Ù¥•Íq€ÁÉ½Á•ÉÑäm%ÍÍÕ”€ŒÌàØÈÈÄÀÜÁt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌàØÈÈÄÀÜÀ¤	Õ¥±Ðµ¥¸-½Ñ±¥¸ÍÕÁÁ½ÉÐ¥¸@Í¡½Õ±¹½ÐÍå¹¡É½¹¥é”Ý¥Ñ Ñ¡”-½Ñ±¥¸Í½ÕÉ•Í•ÑÌm%ÍÍÕ”€ŒÐÌÌÜÔàÈÌÅt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÌÌÜÔàÈÌÄ¤…¥°…¹‘É½¥±¥‰É…ÉäÁÕ‰±¥Í¡¥¹œ¥˜½¹ÍÕµ•È­••À™¥±”½¹Ñ…¥¹Ì€µ‘½¹Ñ½‰™ÕÍ…Ñ”m%ÍÍÕ”€ŒÈÐÄäÔÔÐÀát¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÈÐÄäÔÔÐÀà¤9¼=ÁÑ¥½¹ÌÑ¼AÉ¥¹Ð5…ÁÁ¥¹œ™½È=ÁÑ¥µ¥é•I•Í½ÕÉ•Ìm%ÍÍÕ”€ŒÐÌØÔäÔàÈÙt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÌØÔäÔàÈØ¤5…­”¥Ð…¸•ÉÉ½ÈÑ¼…±°™¥¹…±¥é•Í°…™Ñ•ÈÑ¡¥ÌÁ¡…Í”¡…Ì‰••¸Á…ÍÍ•m%ÍÍÕ”€ŒÐÔÜÀàäØÜÁt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÔÜÀàäØÜÀ¤@¥¹¥Ñ¥…±¥é•Ì©•Ñ¥™¥•È½¹™¥œ•Ù•¸Ý¡•¸©•Ñ¥™¥•È¥Ì‘¥Í…‰±•m%ÍÍÕ”€ŒÐÔÈÈÐØàÄÑt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÔÈÈÐØàÄÐ¤‰Õ¥±Ð¥¸­½Ñ±¥¸‘½•Ì¹½Ð…‘­½Ñ±¥¹MÑ‘±¥ˆ…Ì„½µÁ¥±”Ñ¥µ”‘•Á•¹‘•¹äÝ¡•¸q­½Ñ±¥¸¹ÍÑ‘±¥ˆ¹‘•™…Õ±Ð¹‘•Á•¹‘•¹åq€¥ÌÑÉÕ”Ñ¼µ½‘Õ±”…¹Á½´™¥±•Ìm%ÍÍÕ”€ŒÐÐÈÈÔÀäÀÉt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÈÈÔÀäÀÈ¤9•Ü½ÁÑ¥µ¥é……Ñ¥½¸M0‘½•Ì¹½ÐÉ•…Ñ”½¹™¥ÕÉ…Ñ¥½¸¹ÑáÐ‰ä‘•™…Õ±Ðm%ÍÍÕ”€ŒÐÐÌÔàÜÈØÙt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÌÔàÜÈØØ¤@€à¸ÄÌ¸À™…¥±ÌÑ¼Ù•É¥™ä¹…ØÉ…Á ¥¸„µ½‘Õ±”m%ÍÍÕ”€ŒÐÐÐÈØÀØÈát¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÐÈØÀØÈà¤@ÕÍ•Ì‘•ÁÉ•…Ñ•É…‘±”A$èµÕ±Ñ¤µÍÑÉ¥¹œ¹½Ñ…Ñ¥½¸m%ÍÍÕ”€ŒÌÐÜÜÌÈÌÔÝt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌÐÜÜÌÈÌÔÜ¤]…É¸ÕÍ•ÉÌÑÉå¥¹œÑ¼ÕÍ”±•…äµÕ±Ñ¥‘•à±¥‰É…ÉäÝ¥Ñ µ¥¹M‘­Y•ÉÍ¥½¸pøôÈÄm%ÍÍÕ”€ŒÌÌÌàÌÄÜÌÑt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌÌÌàÌÄÜÌÐ¤‰Õ¥±™…¥±Ì¥˜Ñ¡•É”…É”½‘”•¹•É…Ñ¥½¸Ñ…Í­Ìm%ÍÍÕ”€ŒÐÐØÄÈÌÄÄÅt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐØÄÈÌÄÄÄ¤]¥Ñ q…¹‘É½¥¹‰Õ¥±Ñ%¹-½Ñ±¥¸õ™…±Í•q€…¹q…¹‘É½¥¹¹•ÝÍ°õ™…±Í•q€…¹q…¹‘É½¥¹•¹…‰±•1•…åY…É¥…¹ÑÁ¤õ™…±Í•q€°ÕÍ¥¹œq­½Ñ±¥¸µ…¹‘É½¥‘q€Á±Õ¥¸Ý¥±°™…¥°Ý¥Ñ €‰A$€…ÁÁ±¥…Ñ¥½¹Y…É¥…¹ÑÌœ¥Ì½‰Í½±•Ñ”ˆm%ÍÍÕ”€ŒÐÐÌÀÌÜÌØÕt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÌÀÌÜÌØÔ¤	Õ¥±Ðµ¥¸-½Ñ±¥¸™…¥±ÌÑ¼É•Í½±Ù”Õ¹Ù•ÉÍ¥½¹•­½Ñ±¥¸µÍÑ‘±¥ˆÝ¡•¸­½Ñ±¥¸¹ÍÑ‘±¥ˆ¹‘•™…Õ±Ð¹‘•Á•¹‘•¹äõ™…±Í”m%ÍÍÕ”€ŒÐÐÔäØÜÈÐÑt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÔäØÜÈÐÐ¤•á…Ñ„½Á•¹Ì„™¥±”Ý¥Ñ¡½ÕÐ±½Í¥¹œ°ÁÉ•Ù•¹Ñ¥¹œ±•…¹ÕÀm%ÍÍÕ”€ŒÌØàØÀäÜÌÝt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌØàØÀäÜÌÜ¤¹‘É½¥‘M½ÕÉ•¥É•Ñ½ÉåM•ÐÍ¡½Õ±ÍÑ½À•áÑ•¹‘¥¹œA…ÑÑ•É¹¥±Ñ•É…‰±”¥¸@€ä¸Àm%ÍÍÕ”€ŒÌàäÜÀÜÀÐÅt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌàäÜÀÜÀÐÄ¤Q•ÍÐ¥áÑÕÉ”ÉÉ½È¥¸Ñ•ÍÐ½¹±äµ½‘Õ±•Ìm%ÍÍÕ”€ŒÌÔÌÈÐäÌÐÝt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌÔÌÈÐäÌÐÜ¤%¹½ÉÉ•Ð•ÉÉ½ÈÝ¡•¸ÕÍ¥¹œ½¹Ñ•áÐÉ••¥Ù•ÉÌ¥¸Ñ•ÍÐ™¥áÑÕÉ•Ìm%ÍÍÕ”€ŒÌÔÄÀÐØÄäÝt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌÔÄÀÐØÄäÜ¤%¹½ÉÉ•Ð%•ÉÉ½ÉÌ™½È-½Ñ±¥¸½‘”¥¸Ñ•ÍÑ¥áÑÕÉ•Ìm%ÍÍÕ”€ŒÐÐØààäØÔÉt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐØààäØÔÈ¤q±•…äµ­…ÁÑq€Á±Õ¥¸Í­¥ÁÌ…¹¹½Ñ…Ñ¥½¸ÁÉ½•ÍÍ¥¹œÕ¹±¥­”q­½Ñ±¥¸µ­…ÁÑq€m%ÍÍÕ”€ŒÐÐØÐäÈÀØÅt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐØÐäÈÀØÄ¤½µÁ¥±•M‘­MÁ•Œ¹µ¥¹½ÉÁ¥1•Ù•°¥Ì¹½ÐÝ½É­¥¹œÝ¥Ñ M•ÑÑ¥¹ÍáÑ•¹Í¥½¸m%ÍÍÕ”€ŒÐÈäÈÔÌÔÜåt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÈäÈÔÌÔÜä¤qm™ÕÍ•±¥ˆ€´ÁÕ‰±¥qt•¹•É…Ñ•™ÕÍ•±¥‰É…Éä‘½•Ì¹½Ð¥¹±Õ‘”Í½ÕÉ•Ìm%ÍÍÕ”€ŒÄÐäÜÜÀàØÝt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÄÐäÜÜÀàØÜ¤•áÑÉ…Ñ9…Ñ¥Ù•1¥‰Ì…¹ÕÍ•µ‰•‘‘•‘•àÍ¡½Õ±¹½Ð‰”½µ¥¹œ™É½´Ñ¡”µ…¹¥™•ÍÐm%ÍÍÕ”€ŒÐÐäÄÄÐÔÄát¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐäÄÄÐÔÄà¤]…É¹¥¹Ì™É½´Hà¥¸@€ä¸À¸Àµ…±Á¡„Àäm%ÍÍÕ”€ŒÌØàÐÈØÔäát¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌØàÐÈØÔäà¤I•µ½Ù”‘•ÁÉ•…Ñ•¹‘É½¥‘M½ÕÉ•M•Ð¹©¹¤¥¸@€ä¸Àm%ÍÍÕ”€ŒÌØàÐàÐÐàÍt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌØàÐàÐÐàÌ¤I•µ½Ù”%¹ÍÑ…±±…Ñ¥½¸¹¥¹ÍÑ…±±=ÁÑ¥½¹Ì ¤¥¸@€ä¸Àm%ÍÍÕ”€ŒÌØàÐàÈÐàÑt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌØàÐàÈÐàÐ¤I•µ½Ù”	Õ¥±‘QåÁ”¹¥ÍI•¹‘•ÉÍÉ¥ÁÑ•‰Õ…‰±”¥¸@€ä¸À¸m%ÍÍÕ”€ŒÐÈàØÐØÄÜåt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÈàØÐØÄÜä¤I•µ½Ù”…¹‘É½¥¹‘•™…Õ±ÑÌ¹‰Õ¥±‘™•…ÑÕÉ•Ì¹É•¹‘•ÉÍÉ¥ÁÐm%ÍÍÕ”€ŒÐÌØààÜÌÔát¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÌØààÜÌÔà¤q½´¹…¹‘É½¥¹­½Ñ±¥¸¹µÕ±Ñ¥Á±…Ñ™½É´¹±¥‰É…Éåq€É…Í¡•ÌÝ¥Ñ É…‘±”5…¹…••Ù¥•Ìm%ÍÍÕ”€ŒÐÈàØÐÔÜØÍt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÈàØÐÔÜØÌ¤I•µ½Ù”q…¹‘É½¥¹‘•™…Õ±ÑÌ¹‰Õ¥±‘™•…ÑÕÉ•Ì¹…¥‘±q€‘•™…Õ±ÑÌÉ…‘±”¹ÁÉ½Á•ÉÑ¥•Ì™±…Ìm%ÍÍÕ”€ŒÈäÐÄàÌÀÄát¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÈäÐÄàÌÀÄà¤…¥°‰Õ¥±Ý¡•¸ÁÉ½Õ…É™¥±”‘½•Ì¹½Ð•á¥ÍÐm%ÍÍÕ”€ŒÈÔÐÌÀÔÀÐÅt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÈÔÐÌÀÔÀÐÄ¤É•µ½Ù”‰Õ¥±‘½¹™¥œ‘•™…Õ±ÑÌÉ…‘±”¹ÁÉ½Á•ÉÑ¥•Ì™±…Ìm%ÍÍÕ”€ŒÈàÀØÜÐÈÌÁt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÈàÀØÜÐÈÌÀ¤¡…¹”Ñ¡”…ÁÀÌÑ…É•ÑM‘¬‘•™…Õ±ÐÙ…±Õ”Ñ¼‰”‰…Í•½¸½µÁ¥±•M‘¬¥¹ÍÑ•…½˜µ¥¹M‘¬m%ÍÍÕ”€ŒÐÌØàÜàÔÌÕt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÌØàÜàÔÌÔ¤]¡•¸q¥Í%¹±Õ‘•¹‘É½¥‘I•Í½ÕÉ•Íq€¥Ì•¹…‰±•°qÁÉ½•ÍÍíY…É¥…¹ÑõU¹¥ÑQ•ÍÑ5…¹¥™•ÍÑq€™…¥±ÌÑ¼µ•É”Ñ½½±Ìé½Ù•ÉÉ¥‘•1¥‰É…ÉäÕÍ…•Ì¥¸@€à¸ÄÈ¸Àm%ÍÍÕ”€ŒÐÄÄÜÌäÀàÙt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÄÄÜÌäÀàØ¤@…ÕÍ¥¹œ‘•ÁÉ•…Ñ¥½¸Ý…É¹¥¹Ì¥¸É…‘±”™½È)Y4Ñ•ÍÐÑ…Í­Ìm%ÍÍÕ”€ŒÈÌÔÐÔÜÀÈÅt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÈÌÔÐÔÜÀÈÄ¤•Á•¹‘•¹åI•Á½ÉÑQ…Í¬¥Ì¥¹½µÁ…Ñ¥‰±”Ý¥Ñ Ñ¡”½¹™¥ÕÉ…Ñ¥½¸…¡”m%ÍÍÕ”€ŒÌØäÈÐØÔÔÙt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÌØäÈÐØÔÔØ¤MÝ¥Ñ ‘•™…Õ±ÐÍ½ÕÉ”½Ñ…É•Ð)…Ù„Ù•ÉÍ¥½¸™É½´)…Ù„€àÑ¼)…Ù„€ÄÄ¥¸@€ä¸Àm%ÍÍÕ”€ŒÈÔààÔÔÈÜÕt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÈÔààÔÔÈÜÔ¤±¥À…¹‘É½¥¹ÕÍ•¹‘É½¥‘`‘•™…Õ±ÐÑ¼ÑÉÕ”m%ÍÍÕ”€ŒÐÐÈÜØÌÈÀÁt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÈÜØÌÈÀÀ¤	•ÑÑ•È•á•ÁÑ¥½¸Ý¡•¸…ÁÁ±å¥¹œ­…ÁÐÁ±Õ¥¸Ý¥Ñ ‰Õ¥±Ðµ¥¸-½Ñ±¥¸¸m%ÍÍÕ”€ŒÐÐÄØÜäÈÈÙt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÄØÜäÈÈØ¤…¹‘É½¥¹ÁÉ½Õ…É¹™…¥±=¹5¥ÍÍ¥¹¥±•Ì¥Ì¹½ÐÝ½É­¥¹œ™½È½¹ÍÕµ•ÉAÉ½Õ…É‘¥±•Ìm%ÍÍÕ”€ŒÐÐÌÀÔÄÌäÅt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÌÀÔÄÌäÄ¤UÁ‘…Ñ”-½Ñ±¥¸É…‘±”Á±Õ¥¸‘•Á•¹‘•¹äÑ¼€È¸È¸ÄÀm%ÍÍÕ”€ŒÐÈääàÄÄÌÉt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÈääàÄÄÌÈ¤É•…Ñ”-½Ñ±¥¹)Ùµ¹‘É½¥‘½µÁ¥±…Ñ¥½¸ÕÍ¥¹œ-@A$m%ÍÍÕ”€ŒÐÐÈàØäÜÌÅt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÈàØäÜÌÄ¤-½Ñ±¥¸•áÁ±¥¥ÐA$µ½‘”…ÁÁ±¥•Ñ¼Ñ•ÍÐÍ½ÕÉ•Ì€¨©1¥¹Ð¨¨m%ÍÍÕ”€ŒÐÌÀääÄÔÐåt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÌÀääÄÔÐä¤@€à¸ÄÄ¸Àè±¥¹Ñ¹…±åé•I•±•…Í”Ñ…Í¬É…Í¡•ÌÝ¡•¸…ÁÁ±å¥¹œ€¹É…‘±”¹­ÑÌ™¥±•ÌÝ¥Ñ …ÁÁ±ä¡™É½´€ô€ˆ¸¸¸ˆ¤m%ÍÍÕ”€ŒÐÐÄÔÌØàÈÁt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÄÔÌØàÈÀ¤1¥¹Ð¡•­ÍM‘­%¹ÑÑ1•…ÍÐ¡•¬‘½•Ì¹½Ð¡•¬¥˜Ñ¡”…¹¹½Ñ…Ñ•Ù…±Õ”¥Ì½ÉÉ•Ðm%ÍÍÕ”€ŒÐÐØØäØØÄÍt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐØØäØØÄÌ¤	Õ¥±Ðµ¥¸-½Ñ±¥¸‘½•Ì¹½Ð…‘€¹­½Ñ±¥¹}µ½‘Õ±”Ñ¼5Qµ%9m%ÍÍÕ”€ŒÐÐäÀÌÄÔÀÕt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐäÀÌÄÔÀÔ¤1¥¹Ð±…ÍÍÁ…Ñ ½¹Ñ…¥¹Ì‘ÕÁ±¥…Ñ”±…ÍÍ•Ì…Ð‘¥™™•É•¹ÐÙ•ÉÍ¥½¹Ìm%ÍÍÕ”€ŒÐÐàÄÐàÌÔÁt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐàÄÐàÌÔÀ¤=Ù•ÉÉ¥‘¥¹œÁÉ¥Ù…Ñ”É•Í½ÕÉ•ÌÝ½É­…É½Õ¹¹½ÐÝ½É­¥¹œ€¡Ñ½½±Ìé½Ù•ÉÉ¥‘”€ô€‰ÑÉÕ”ˆ¤m%ÍÍÕ”€ŒÐÀÔØÜØÜÄÉt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÀÔØÜØÜÄÈ¤	ÕœèÉ•µ½Ù…°½˜Õ¹ÕÍ•É•Í½ÕÉ•Ì‘½•Í¸Ð…±Í¼É•µ½Ù”Ñ¡”ÑÉ…¹Í±…Ñ¥½¹Ì½˜Ñ¡•´°…¹‘½•Í¸Ð…Í¬…‰½ÕÐ¥Ð•¥Ñ¡•Èm%ÍÍÕ”€ŒÐÐÀÐÄÔØÌÙt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÀÐÄÔØÌØ¤1¥¹ÐÑ¡É½Ý¥¹œÝ…É¹¥¹œ€‰½Õ±¹½Ð±•…¸ÕÀ,È…¡•Ìˆm%ÍÍÕ”€ŒÐÐÀÐÄÔØÌÙt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÀÐÄÔØÌØ¤1¥¹ÐÑ¡É½Ý¥¹œÝ…É¹¥¹œ€‰½Õ±¹½Ð±•…¸ÕÀ,È…¡•Ìˆ€¨©1¥¹Ð%¹Ñ•É…Ñ¥½¸¨¨m%ÍÍÕ”€ŒÐØÀÀØàÜäát¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐØÀÀØàÜäà¤¹‘É½¥‘1¥¹Ñ¹…±åÍ¥ÍQ…Í¬…¡”µ¥ÍÍ•Ì…É½ÍÌ‘¥™™•É•¹Ð),Ù•¹‘½ÉÌ½Èµ¥¹½ÈÙ•ÉÍ¥½¹Ì‘Õ”Ñ¼ÍåÍÑ•µAÉ½Á•ÉÑå%¹ÁÕÑÌ¹©…Ù…Y•ÉÍ¥½¸‘¥™™•É•¹•Ìm%ÍÍÕ”€ŒÐÐÐÐÐÜÀÀÉt¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÐÐÐÐÜÀÀÈ¤1¥¹Ð…ÕÑ½µ…Ñ¥…±±äÕÍ•Ì±…Ñ•ÍÐ¥¹ÍÑ…±±•M,‘•ÍÁ¥Ñ”½µÁ¥±•M‘¬°‘½•Í¸ÐÉ•¥ÍÑ•È…ÌÑ…Í¬¥¹ÁÕÐ…¹‰É•…­Ì…¡¥¹œ€¨©M¡É¥¹­•È€¡Hà¤¨¨m%ÍÍÕ”€ŒÐÔÐäÈÜÐàát¡¡ÑÑÁÌè¼½¥ÍÍÕ•ÑÉ…­•È¹½½±”¹½´½¥ÍÍÕ•Ì¼ÐÔÐäÈÜÐàà¤Hà½ÁÑ¥µ¥é•É•Í½ÕÉ”Í¡É¥¹­¥¹œÍ¥±•¹Ñ±ä™…¥±Ì¥˜ÕÍ¥¹œ™¥¹…°É•Í½ÕÉ”%Ì((ñ‰È€¼ø