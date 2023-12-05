/*
 * Copyright (C) 2023 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

import org.jetbrains.gradle.ext.GradleTask
import org.jetbrains.gradle.ext.JarApplication
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.settings
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation

plugins {
    // Apply core plugins.
    distribution
    `java-library`

    // Apply third-party plugins.
    alias(libs.plugins.detekt)
    alias(libs.plugins.ideaExt)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
}

configurations {
    // Create an extended configuration with the analyzer CLI to run the plugin from an IDEA run configuration.
    resolvable("analyzerCliClasspath").get().extendsFrom(project.configurations["runtimeClasspath"])
}

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = "analyzer-cli"

    // Extend the "runtimeClasspath", which is the configuration that "Shadow" uses by default, with the classpath for
    // the ORT main CLI and the analyzer command.
    configurations = listOf(project.configurations["analyzerCliClasspath"])

    manifest.attributes["Main-Class"] = "org.ossreviewtoolkit.cli.OrtMainKt"
}

idea {
    project {
        settings {
            runConfigurations {
                create<JarApplication>("ORT Analyzer CLI Template") {
                    beforeRun {
                        create("Build Shadow JAR", GradleTask::class) {
                            task = shadowJar.get()
                        }
                    }

                    jarPath = shadowJar.get().outputs.files.singleFile.path
                    programParameters = "-Port.forceOverwrite=true --info analyze -i <input-dir> -o <output-dir>"
                }
            }
        }
    }
}

distributions {
    main {
        contents {
            from(tasks["jar"])
            from(configurations["runtimeClasspath"])
        }
    }
}

testing {
    suites {
        withType<JvmTestSuite>().configureEach {
            useJUnitJupiter()

            dependencies {
                implementation(libs.kotestAssertionsCore)
                implementation(libs.kotestRunnerJunit5)

                runtimeOnly(libs.log4jApiToSlf4j)
                runtimeOnly(libs.logbackClassic)
            }
        }

        register<JvmTestSuite>("funTest") {
            sources {
                kotlin {
                    testType = TestSuiteType.FUNCTIONAL_TEST
                }
            }

            dependencies {
                implementation(project())
            }
        }
    }
}

// Associate the "funTest" compilation with the "main" compilation to be able to access "internal" objects from
// functional tests.
kotlin.target.compilations.apply {
    getByName("funTest").associateWith(getByName(KotlinCompilation.MAIN_COMPILATION_NAME))
}

dependencies {
    compileOnlyApi(libs.ortAnalyzer)
    compileOnlyApi(libs.ortModel)

    detektPlugins(libs.detektFormatting)
    detektPlugins(libs.ortDetektRules)

    implementation(libs.log4jApiKotlin)
    implementation(libs.ortCommonUtils)
    implementation(libs.ortSpdxPackageManager)

    "analyzerCliClasspath"(libs.ortAnalyzerCommand)
    "analyzerCliClasspath"(libs.ortCli)

    "funTestImplementation"(libs.ortTestUtils)
    "funTestImplementation"(libs.ortVcsGit)
    "funTestImplementation"(variantOf(libs.ortAnalyzer) { classifier("test-fixtures") })
}

detekt {
    config.from(files(".detekt.yml"))
    buildUponDefaultConfig = true
}
